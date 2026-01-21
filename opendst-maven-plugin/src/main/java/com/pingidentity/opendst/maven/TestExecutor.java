/*
 * Copyright 2026 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingidentity.opendst.maven;

import static com.pingidentity.opendst.maven.Commons.FAILURE_BASE_DIR;
import static com.pingidentity.opendst.maven.Commons.JAVA_BASE_OPTIONS;
import static com.pingidentity.opendst.maven.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.maven.Commons.LOG_SOURCE_SIMULATOR;
import static com.pingidentity.opendst.maven.Commons.deleteRecursively;
import static java.lang.Runtime.getRuntime;
import static java.lang.Thread.currentThread;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createFile;
import static java.util.Arrays.asList;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.ThreadLocalRandom.current;

import com.pingidentity.opendst.Plan;
import com.pingidentity.opendst.maven.Commons.SignalEvent;
import com.pingidentity.opendst.maven.OpenDstMojo.LogStatement;
import com.pingidentity.opendst.maven.Orchestrator.ExecutionPlan;
import com.pingidentity.opendst.maven.Signal.SignalType;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JacksonException;

/**
 * Executes OpenDST simulations for a single test class in external JVM processes.
 *
 * <p>Usage: construct, call {@link #execute()}, read the boolean result.
 */
final class TestExecutor {
    private static final String JAVA_BIN =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();

    /** JVM launch configuration for child processes. */
    record JvmConfig(
            Path instrumentedWarsDir, String agentJarPath, String jvmArguments, String debugArgs, File logSpy) {}

    /** Execution loop control parameters. */
    record RunConfig(double replayProbability, boolean isDebugOrReplay, int stagnationLimit, int parallelism) {}

    private final Path testBasePath;
    private final String testClass;
    private final String testMethod;
    private final String classpath;
    private final Path failureDir;
    private final JvmConfig jvm;
    private final RunConfig run;
    private final OpenDstLogger logger;
    private final Orchestrator orchestrator;
    private final ReportGenerator reportGenerator;

    private final AtomicBoolean failureFound = new AtomicBoolean(false);
    private final AtomicBoolean stagnationReached = new AtomicBoolean(false);
    private final AtomicLong runCount = new AtomicLong(0);
    private final AtomicLong lastInterestingRun = new AtomicLong(0);
    private final Queue<Plan> pastPlans = new ArrayBlockingQueue<>(10);

    TestExecutor(
            Path testBasePath,
            String testClass,
            String testMethod,
            String classpath,
            Set<Instrumentation.DiscoveredProperty> discoveredProperties,
            JvmConfig jvm,
            OpenDstLogger logger,
            Orchestrator orchestrator,
            RunConfig run) {
        this.testBasePath = testBasePath;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.classpath = classpath;
        this.failureDir = testBasePath.resolve(FAILURE_BASE_DIR);
        this.jvm = jvm;
        this.run = run;
        this.logger = logger;
        this.orchestrator = orchestrator;
        this.reportGenerator = new ReportGenerator(
                testBasePath.resolve("report.html"), testClass + "#" + testMethod, discoveredProperties);
    }

    /**
     * Runs the full simulation lifecycle: parallel run loops, property verification, and report generation.
     *
     * @return {@code true} if the test failed
     */
    boolean execute() throws InterruptedException {
        try (var threadPool = newFixedThreadPool(run.parallelism())) {
            var futures = new ArrayList<Future<Void>>();
            for (int i = 0; i < run.parallelism(); i++) {
                int count = i;
                futures.add(threadPool.submit(() -> {
                    runLoop(count);
                    return null;
                }));
            }
            try {
                for (var future : futures) {
                    future.get();
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Orchestrator.SimulationFailureException) {
                    failureFound.set(true);
                    return true;
                }
                throw new RuntimeException("Test '%s#%s' failed unexpectedly".formatted(testClass, testMethod), e);
            } finally {
                threadPool.shutdownNow();
            }

            try {
                reportGenerator.checkResults();
            } catch (Orchestrator.SimulationFailureException e) {
                failureFound.set(true);
            }
        } finally {
            reportGenerator.finish();
            logger.raw().info("OpenDST static report saved to: %s".formatted(testBasePath.resolve("report.html")));
        }
        return failureFound.get();
    }

    private void runLoop(int count) throws InterruptedException, IOException {
        for (; ; ) {
            var executionPlan = getNewExecutionPlanOrReplay();
            if (runOnce(createRunBaseDir(testBasePath, count), executionPlan)) {
                lastInterestingRun.set(runCount.get());
            }
            runCount.incrementAndGet();
            if (run.isDebugOrReplay() || failureFound.get() || stagnationReached.get()) {
                return;
            } else if (runCount.get() - lastInterestingRun.get() > run.stagnationLimit()) {
                stagnationReached.set(true);
                logger.raw()
                        .info("Stagnation limit reached: %d runs without new discovery. Stopping."
                                .formatted(run.stagnationLimit()));
                return;
            }
        }
    }

    private ExecutionPlan getNewExecutionPlanOrReplay() {
        if (current().nextDouble() < run.replayProbability()) {
            var plan = pastPlans.poll();
            if (plan != null) {
                logger.run("check")
                        .withSeed(plan.segments().getLast().seed())
                        .withHash(plan.hash())
                        .withDuration(plan.segments().getLast().iteration())
                        .log();
                return new ExecutionPlan(plan, _ -> false);
            }
        }
        return orchestrator.nextPlan();
    }

    private boolean runOnce(Path runBaseDir, ExecutionPlan execution) throws IOException {
        var proc = startProcess(runBaseDir);
        var runKiller = new Thread(proc::destroyForcibly);
        getRuntime().addShutdownHook(runKiller);
        try {
            JSON_MAPPER.writeValue(proc.getOutputStream(), execution.plan());
            return monitorProcessOutput(proc, execution);
        } catch (InterruptedException e) {
            currentThread().interrupt();
            return false;
        } finally {
            proc.destroyForcibly();
            getRuntime().removeShutdownHook(runKiller);
        }
    }

    private Process startProcess(Path runBaseDir) throws IOException {
        return new ProcessBuilder(buildJvmCommandLine(runBaseDir))
                .directory(runBaseDir.toFile())
                .redirectErrorStream(true)
                .start();
    }

    private boolean monitorProcessOutput(Process proc, ExecutionPlan execution)
            throws IOException, InterruptedException {
        reportGenerator.onPlanStarted(execution.plan());
        boolean interestingRun = false;
        try (var logs = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            int hash = 0;
            LogStatement lastLogReceived = null;
            for (var line = logs.readLine(); line != null; line = logs.readLine()) {
                LogStatement log;
                if ((log = parseLog(line)) != null) {
                    lastLogReceived = log;
                    var extractedSignal = extractSignal(log);
                    if (extractedSignal != null) {
                        if (extractedSignal.signal() instanceof Signal.LifecycleSignal lifecycle
                                && "stopped".equals(lifecycle.message())) {
                            hash = lifecycle.hash();
                        }
                        interestingRun |= processSignal(extractedSignal, execution.monitor());
                    }
                }
            }
            onPlanTerminated(execution.plan(), proc.waitFor(), lastLogReceived, hash);
            if (execution.plan().hash() == 0) {
                pastPlans.offer(execution.plan().withHash(hash));
            }
        }
        return interestingRun;
    }

    private LogStatement parseLog(String line) {
        if (line.length() > 2 && line.charAt(0) == '{' && line.charAt(line.length() - 1) == '}') {
            try {
                return JSON_MAPPER.readValue(line, LogStatement.class);
            } catch (JacksonException e) {
                // Ignore invalid format, the line will be logged below
            }
        }
        logger.raw().debug(line);
        return null;
    }

    private SignalEvent extractSignal(LogStatement log) {
        if (!LOG_SOURCE_SIMULATOR.equals(log.source())) {
            return null;
        }
        try {
            var logEntry = log.log().deepCopy();
            if (logEntry.isObject()) {
                ((tools.jackson.databind.node.ObjectNode) logEntry)
                        .put("fullJson", log.log().toString());
            }
            var signal = JSON_MAPPER.treeToValue(logEntry, Signal.class);
            return new SignalEvent(log.iteration(), signal);
        } catch (Exception e) {
            // Ignore badly formatted log
            return null;
        }
    }

    private boolean processSignal(SignalEvent signal, Orchestrator.SignalMonitor monitor) {
        if (signal.signal().type() != SignalType.FAULT) {
            reportGenerator.onSignalHit(signal);
        }
        if (signal.signal().type() == SignalType.LIFECYCLE || signal.signal().type() == SignalType.FAULT) {
            return false;
        }
        return monitor.onSignalHit(signal);
    }

    private void onPlanTerminated(Plan plan, int code, LogStatement lastLog, int runHash) {
        if (code != 0) {
            // Synthesize lifecycle failure signal for non-zero exit
            var fullJson = "{\"message\":\"stopped\",\"type\":\"lifecycle\",\"reason\":\"internal_error\"}";
            var lifecycleSignal = new Signal.LifecycleSignal("stopped", "internal_error", 0, fullJson);
            reportGenerator.onSignalHit(new SignalEvent(-1, lifecycleSignal));
        }

        if (plan.hash() != 0 && code == 0) {
            if (plan.hash() == runHash) {
                logger.run("verified")
                        .withSeed(plan.segments().getLast().seed())
                        .withHash(plan.hash())
                        .withDuration(plan.segments().getLast().iteration())
                        .log();
            } else {
                logger.run("flaky")
                        .error()
                        .withSeed(plan.segments().getLast().seed())
                        .withExpectedHash(plan.hash())
                        .withCurrentHash(runHash)
                        .log();
                failureFound.set(true);
            }
        } else if (code == 2) {
            logger.run("flaky")
                    .error()
                    .withSeed(plan.segments().getLast().seed())
                    .withExpectedHash(plan.hash())
                    .withCurrentHash(runHash)
                    .log();
            failureFound.set(true);
        } else if (code != 0) {
            logger.run("failed")
                    .error()
                    .withSeed(plan.segments().getLast().seed())
                    .withCode(code)
                    .withLastLog(lastLog != null ? lastLog.log() : "<none>")
                    .log();
            savePlan(plan.withHash(runHash));
            failureFound.set(true);
        }
    }

    private void savePlan(Plan plan) {
        try {
            createDirectories(failureDir);
            for (int i = 0; i < 1000; i++) {
                var failureFile = failureDir.resolve("failure-%d.json".formatted(i));
                try {
                    createFile(failureFile);
                } catch (FileAlreadyExistsException e) {
                    continue;
                }
                JSON_MAPPER.writeValue(failureFile, plan);
                logger.raw().error("\tPlan saved to '%s'".formatted(failureDir.relativize(failureFile)));
                return;
            }
        } catch (IOException e) {
            logger.raw().error("\tUnable to save the failing plan: " + e.getMessage());
        }
        throw new Orchestrator.SimulationFailureException();
    }

    private List<String> buildJvmCommandLine(Path runBaseDir) {
        var runTmpDir = runBaseDir.resolve("tmp");
        if (!runTmpDir.toFile().mkdirs()) {
            throw new IllegalStateException("The run tmp directory already exists: %s".formatted(runBaseDir));
        }
        var command = new ArrayList<String>();
        command.add(JAVA_BIN);
        command.addAll(JAVA_BASE_OPTIONS);
        command.addAll(List.of(
                "-javaagent:%s".formatted(jvm.agentJarPath()),
                "-Djava.io.tmpdir=%s".formatted(runTmpDir),
                "-Dopendst.wars-dir=%s".formatted(jvm.instrumentedWarsDir())));
        var spy = jvm.logSpy() != null
                ? jvm.logSpy()
                : runBaseDir.resolve("simulator.log").toFile();
        command.add("-Dopendst.log-spy=%s".formatted(spy.getAbsolutePath()));
        if (jvm.jvmArguments() != null && !jvm.jvmArguments().isBlank()) {
            command.addAll(asList(jvm.jvmArguments().split(" +")));
        }
        if (jvm.debugArgs() != null) {
            command.addAll(asList(jvm.debugArgs().split(" +")));
        }
        command.addAll(asList("-cp", classpath, OpenDstRunner.class.getName(), testClass, testMethod));
        return List.copyOf(command);
    }

    private Path createRunBaseDir(Path testBasePath, int count) throws IOException {
        var runBaseDir = testBasePath
                .resolve(Commons.RUNS_BASE_DIR)
                .resolve(Commons.RUN_DIR_FORMAT.formatted(count))
                .toAbsolutePath();
        deleteRecursively(testBasePath, runBaseDir);
        if (!runBaseDir.toFile().mkdirs()) {
            throw new IllegalStateException(
                    "The run base dir '%s' is unexpectedly already present".formatted(runBaseDir));
        }
        return runBaseDir;
    }
}
