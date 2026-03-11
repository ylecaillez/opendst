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
package com.pingidentity.opendst.runner;

import static com.pingidentity.opendst.runner.Commons.JAVA_BASE_OPTIONS;
import static com.pingidentity.opendst.runner.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.runner.Commons.deleteRecursively;
import static java.lang.Runtime.getRuntime;
import static java.lang.Thread.ofVirtual;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.util.Arrays.asList;
import static java.util.concurrent.ThreadLocalRandom.current;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pingidentity.opendst.Plan;
import com.pingidentity.opendst.runner.Commons.SignalEvent;
import com.pingidentity.opendst.runner.Orchestrator.ExecutionPlan;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Executes OpenDST simulations for a single test class in external JVM processes. */
final class TestExecutor {
    private static final String JAVA_BIN =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();

    record LogStatement(@JsonProperty("it") long iteration, String source, JsonNode log) {}

    /** JVM launch configuration for child processes. */
    record JvmConfig(
            Path instrumentedWarsDir,
            String agentJarPath,
            String jvmArguments,
            String debugArgs,
            File logSpy,
            String mainClass) {}

    /** Execution loop control parameters. */
    record RunConfig(
            double replayProbability, boolean isDebugOrReplay, int stagnationLimit, int forkCount, boolean failFast) {}

    private final Path reportDir;
    private final Path runsDir;
    private final String testClass;
    private final String testMethod;
    private final String classpath;
    private final JvmConfig jvm;
    private final RunConfig runConfig;
    private final OpenDstLogger logger;
    private final Orchestrator orchestrator;

    private final AtomicInteger runSequence = new AtomicInteger();
    private final AtomicInteger lastInterestingRun = new AtomicInteger();
    private final Queue<Plan> pastPlans = new ArrayBlockingQueue<>(10);
    private volatile boolean failureDetected;

    TestExecutor(
            Path reportDir,
            Path runsDir,
            String testClass,
            String testMethod,
            String classpath,
            JvmConfig jvm,
            OpenDstLogger logger,
            Orchestrator orchestrator,
            RunConfig run) {
        this.reportDir = reportDir;
        this.runsDir = runsDir;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.classpath = classpath;
        this.jvm = jvm;
        this.runConfig = run;
        this.logger = logger;
        this.orchestrator = orchestrator;
    }

    /** Runs the full simulation lifecycle: parallel run loops, property verification, and report generation. */
    void execute(ReportGenerator reportGenerator) throws ExecutionException, InterruptedException {
        var futures = new ArrayList<Future<Void>>();
        for (int i = 0; i < runConfig.forkCount(); i++) {
            int count = i;
            var future = new FutureTask<>(() -> runLoop(count, reportGenerator));
            ofVirtual().name("opendst-executor-" + i).start(future);
            futures.add(future);
        }
        for (var future : futures) {
            future.get();
        }
    }

    private Void runLoop(int count, ReportGenerator reportGenerator) throws IOException, InterruptedException {
        for (; ; ) {
            if (failureDetected) {
                return null;
            }
            var executionPlan = getNewExecutionPlanOrReplay();
            int runCount = runSequence.incrementAndGet();

            var runBaseDir = createRunBaseDir(count);
            ExecutionResult executionResult;
            try {
                executionResult = runOnce(runBaseDir, executionPlan);
            } catch (IOException | InterruptedException e) {
                deleteRecursively(runsDir, runBaseDir);
                throw e;
            }

            if (runConfig.isDebugOrReplay()) {
                deleteRecursively(runsDir, runBaseDir);
                return null;
            }
            if (executionResult.runFailed()) {
                logger.run("fail")
                        .error()
                        .withSeed(executionPlan.plan().segments().getLast().seed())
                        .log();
            } else if (executionPlan.plan().hash() == 0) {
                pastPlans.offer(executionPlan.plan().withHash(executionResult.runHash()));
            } else if (executionPlan.plan().hash() == executionResult.runHash()) {
                logger.run("verified")
                        .withSeed(executionPlan.plan().segments().getLast().seed())
                        .log();
            } else {
                logger.run("flaky")
                        .error()
                        .withSeed(executionPlan.plan().segments().getLast().seed())
                        .log();
            }

            var hexHash = HexFormat.of().toHexDigits(executionResult.runHash());
            var planFile = reportDir.resolve("plans").resolve(hexHash + ".plan.json");
            savePlan(executionPlan.plan().withHash(executionResult.runHash()), planFile);
            reportGenerator.addExecutionResult(executionResult, planFile);

            // Capture simulator.log for interesting runs before deleting the run directory
            if (executionResult.isInteresting()) {
                var simulatorLog = runBaseDir.resolve("simulator.log");
                if (exists(simulatorLog)) {
                    var logDest = reportDir.resolve("plans").resolve(hexHash + ".log.json");
                    copy(simulatorLog, logDest, REPLACE_EXISTING);
                }
            }

            // Run directory is ephemeral — clean up after capturing any interesting artifacts
            deleteRecursively(runsDir, runBaseDir);

            if (runConfig.failFast() && reportGenerator.hasFailures()) {
                logger.raw().info("Assertion failure detected — stopping (--fail-fast)");
                failureDetected = true;
            }

            if (executionResult.isInteresting()) {
                reportGenerator.generate(reportDir.resolve("report.json"));
                int lastRun;
                do {
                    lastRun = lastInterestingRun.get();
                } while (lastRun < runCount && !lastInterestingRun.compareAndSet(lastRun, runCount));
            } else if (runCount - lastInterestingRun.get() >= runConfig.stagnationLimit()) {
                return null;
            }
        }
    }

    private static void savePlan(Plan plan, Path file) throws IOException {
        try (var os = newOutputStream(file, CREATE_NEW)) {
            JSON_MAPPER.writeValue(os, plan);
        } catch (FileAlreadyExistsException e) {
            // Ignore: This plan has already been saved
        }
    }

    private ExecutionPlan getNewExecutionPlanOrReplay() {
        if (current().nextDouble() < runConfig.replayProbability()) {
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

    private ExecutionResult runOnce(Path runBaseDir, ExecutionPlan execution) throws IOException, InterruptedException {
        Process proc = null;
        Thread runKiller = null;
        try {
            proc = startProcess(runBaseDir);
            runKiller = new Thread(proc::destroyForcibly);
            getRuntime().addShutdownHook(runKiller);
            JSON_MAPPER.writeValue(proc.getOutputStream(), execution.plan());
            return monitorExecutionOutput(proc, execution);
        } finally {
            if (proc != null) {
                proc.destroyForcibly();
            }
            if (runKiller != null) {
                try {
                    getRuntime().removeShutdownHook(runKiller);
                } catch (IllegalStateException ignored) {
                    // JVM is shutting down; hook will run naturally
                }
            }
        }
    }

    private Process startProcess(Path runBaseDir) throws IOException {
        return new ProcessBuilder(buildJvmCommandLine(runBaseDir))
                .directory(runBaseDir.toFile())
                .redirectErrorStream(true)
                .start();
    }

    private ExecutionResult monitorExecutionOutput(Process proc, ExecutionPlan execution)
            throws IOException, InterruptedException {
        var lastLogs = new ArrayDeque<String>();
        var result = new ExecutionResult();
        try (var logs = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            for (var line = logs.readLine(); line != null; line = logs.readLine()) {
                if (lastLogs.size() >= 50) {
                    lastLogs.removeFirst();
                }
                lastLogs.addLast(line);
                LogStatement log;
                SignalEvent event;
                if ((log = parseLog(line)) != null && ((event = extractSignal(log))) != null) {
                    boolean interestingEvent = execution.interesting().test(event);
                    if (result.addSignal(event, interestingEvent)) {
                        return result;
                    }
                }
            }
            // For some reason (hopefully captured in the last log line), the process exited but the "stopped" signal
            // has not been received.
            logger.raw()
                    .error("opendst-agent exited unexpectedly with code %d. Here are the latest logs received:\n%s"
                            .formatted(proc.waitFor(), String.join("\n", lastLogs)));
            throw new IOException("opendst-agent failed unexpectedly (exit code %d)".formatted(proc.waitFor()));
        }
    }

    private LogStatement parseLog(String line) {
        if (isJson(line)) {
            try {
                return JSON_MAPPER.readValue(line, LogStatement.class);
            } catch (JacksonException e) {
                // Ignore invalid format, the line will be logged below
            }
        }
        logger.raw().debug(line);
        return null;
    }

    private static boolean isJson(String line) {
        return line.length() > 2 && line.charAt(0) == '{' && line.charAt(line.length() - 1) == '}';
    }

    private SignalEvent extractSignal(LogStatement log) {
        try {
            return new SignalEvent(log.iteration(), JSON_MAPPER.treeToValue(log.log(), Signal.class));
        } catch (JacksonException e) {
            // Ignore badly formatted log
            return null;
        }
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
        command.addAll(asList("-cp", classpath, jvm.mainClass(), testClass, testMethod));
        return List.copyOf(command);
    }

    private Path createRunBaseDir(int count) throws IOException {
        var runBaseDir =
                runsDir.resolve(Commons.RUN_DIR_FORMAT.formatted(count)).toAbsolutePath();
        deleteRecursively(runsDir, runBaseDir);
        if (!runBaseDir.toFile().mkdirs()) {
            throw new IllegalStateException(
                    "The run base dir '%s' is unexpectedly already present".formatted(runBaseDir));
        }
        return runBaseDir;
    }
}
