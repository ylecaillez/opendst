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

import static com.pingidentity.opendst.Constants.APPS_DIR_PROPERTY;
import static com.pingidentity.opendst.runner.Commons.JAVA_BASE_OPTIONS;
import static com.pingidentity.opendst.runner.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.runner.Commons.deleteRecursively;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.out;
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
import com.pingidentity.opendst.runner.BuildRunner.StopCondition;
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
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Executes OpenDST simulations for a single test class in external JVM processes. */
final class TestExecutor {
    private static final String JAVA_BIN =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();

    /** Number of consecutive infrastructure crashes before aborting all forks. */
    private static final int MAX_CONSECUTIVE_CRASHES = 3;

    /** How often (in runs) to emit a progress line to the console. */
    private static final int PROGRESS_INTERVAL = 10;

    record LogStatement(@JsonProperty("it") long iteration, String source, JsonNode log) {}

    /** JVM launch configuration for child processes. */
    record JvmConfig(
            Path instrumentedAppsDir,
            String agentJarPath,
            String patchModuleJarPath,
            String jvmArguments,
            String debugArgs,
            File logSpy,
            String mainClass) {}

    /** Execution loop control parameters. */
    record RunConfig(
            double replayProbability,
            boolean isDebugOrReplay,
            int stagnationLimit,
            int forkCount,
            Set<BuildRunner.StopCondition> stopConditions) {}

    /**
     * {@link ExecutionBackend} that spawns a fresh child JVM per iteration.
     * This is the default backend; it maps 1:1 to the pre-refactor behaviour.
     */
    final class ForkBackend implements ExecutionBackend {
        private Process proc;
        private BufferedReader reader;
        private Thread runKiller;
        private Path runBaseDir;

        ForkBackend(Path runBaseDir) throws IOException {
            this.runBaseDir = runBaseDir;
            proc = startProcess(runBaseDir);
            reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            runKiller = new Thread(proc::destroyForcibly);
            getRuntime().addShutdownHook(runKiller);
        }

        @Override
        public void startIteration(Plan plan) throws IOException {
            JSON_MAPPER.writeValue(proc.getOutputStream(), plan);
        }

        @Override
        public BufferedReader logReader() {
            return reader;
        }

        @Override
        public int awaitCrash() throws InterruptedException {
            proc.waitFor();
            return proc.exitValue();
        }

        @Override
        public void afterIteration() throws IOException, InterruptedException {
            proc.destroy();
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            if (runKiller != null) {
                try {
                    getRuntime().removeShutdownHook(runKiller);
                } catch (IllegalStateException ignored) {
                    // JVM is shutting down; hook will run naturally
                }
                runKiller = null;
            }
        }

        @Override
        public boolean isHealthy() {
            return true; // Each iteration spawns fresh — always healthy at start
        }

        @Override
        public Optional<Path> simulatorLogPath() {
            return Optional.of(runBaseDir.resolve("simulator.log"));
        }

        @Override
        public void cleanupAfterRun() throws IOException {
            deleteRecursively(runsDir, runBaseDir);
        }

        @Override
        public void close() {
            // Nothing — process already terminated in afterIteration
        }

        private Process startProcess(Path runBaseDir) throws IOException {
            return new ProcessBuilder(buildJvmCommandLine(runBaseDir))
                    .directory(runBaseDir.toFile())
                    .redirectErrorStream(true)
                    .start();
        }
    }

    private final Path reportDir;
    private final Path runsDir;
    private final String testClass;
    private final String testMethod;
    private final String classpath;
    private final JvmConfig jvm;
    private final RunConfig runConfig;
    private final OpenDstLogger logger;
    private final Orchestrator orchestrator;
    // Factory that produces a fresh backend for each fork thread.
    // Fork backend: creates a new ForkBackend per iteration.
    // Nyx backend: returns the same persistent NyxBackend (shim stays alive).
    private final Supplier<ExecutionBackend> backendFactory;

    private final AtomicInteger runSequence = new AtomicInteger();
    private final AtomicInteger boringRunStreak = new AtomicInteger();
    private final Queue<Plan> pastPlans = new ArrayBlockingQueue<>(10);
    private final AtomicBoolean earlyExit = new AtomicBoolean();

    /** Constructs a TestExecutor using the default fork-per-iteration backend. */
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
        this(reportDir, runsDir, testClass, testMethod, classpath, jvm, logger, orchestrator, run, null);
    }

    /** Constructs a TestExecutor with an explicit backend factory (null → fork backend). */
    TestExecutor(
            Path reportDir,
            Path runsDir,
            String testClass,
            String testMethod,
            String classpath,
            JvmConfig jvm,
            OpenDstLogger logger,
            Orchestrator orchestrator,
            RunConfig run,
            Supplier<ExecutionBackend> backendFactory) {
        this.reportDir = reportDir;
        this.runsDir = runsDir;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.classpath = classpath;
        this.jvm = jvm;
        this.runConfig = run;
        this.logger = logger;
        this.orchestrator = orchestrator;
        this.backendFactory = backendFactory;
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
        int consecutiveCrashes = 0;

        // For nyx backend: create the persistent backend once per fork thread and reuse it.
        // For fork backend: backendFactory is null — a fresh ForkBackend is created per iteration.
        ExecutionBackend persistentBackend = backendFactory != null ? backendFactory.get() : null;

        try {
            for (; ; ) {
                if (earlyExit.get()) {
                    return null;
                }
                var executionPlan = getNewExecutionPlanOrReplay();
                int runCount = runSequence.incrementAndGet();

                // Nyx: reuse persistent backend; Fork: create fresh backend each iteration
                ExecutionBackend backend;
                if (persistentBackend != null) {
                    if (!persistentBackend.isHealthy()) {
                        // Shim crashed — treat as infrastructure crash and abort this fork
                        logger.raw().error("nyx-lite shim is no longer healthy — aborting fork");
                        earlyExit.set(true);
                        return null;
                    }
                    backend = persistentBackend;
                } else {
                    var runBaseDir = createRunBaseDir(count);
                    backend = new ForkBackend(runBaseDir);
                }

                ExecutionResult executionResult;
                try {
                    executionResult = runOnce(backend, executionPlan);
                } catch (IOException | InterruptedException e) {
                    backend.cleanupAfterRun();
                    throw e;
                }

                // Detect infrastructure crashes (child JVM dies before the simulator starts).
                if (executionResult.isInfrastructureCrash()) {
                    consecutiveCrashes++;
                    if (consecutiveCrashes >= MAX_CONSECUTIVE_CRASHES) {
                        logger.raw()
                                .error(
                                        "Child JVM crashed %d times consecutively without starting the simulation — aborting"
                                                .formatted(consecutiveCrashes));
                        earlyExit.set(true);
                        backend.cleanupAfterRun();
                        return null;
                    }
                } else {
                    consecutiveCrashes = 0;
                }

                if (runConfig.isDebugOrReplay()) {
                    backend.cleanupAfterRun();
                    return null;
                }
                if (executionResult.runFailed()) {
                    logger.run("fail")
                            .error()
                            .withHash(executionResult.runHash())
                            .log();
                } else if (executionPlan.plan().hash() == 0) {
                    pastPlans.offer(executionPlan.plan().withHash(executionResult.runHash()));
                } else if (executionPlan.plan().hash() == executionResult.runHash()) {
                    logger.run("verified").withHash(executionResult.runHash()).log();
                }

                var hexHash = HexFormat.of().toHexDigits(executionResult.runHash());
                var planFile = reportDir.resolve("plans").resolve(hexHash + ".plan.json");
                savePlan(executionPlan.plan().withHash(executionResult.runHash()), planFile);
                reportGenerator.addExecutionResult(executionResult, planFile);

                // Capture simulator.log for interesting runs before deleting the run directory
                if (executionResult.isInteresting()) {
                    backend.simulatorLogPath().ifPresent(simulatorLog -> {
                        if (exists(simulatorLog)) {
                            var logDest = reportDir.resolve("plans").resolve(hexHash + ".log.json");
                            try {
                                copy(simulatorLog, logDest, REPLACE_EXISTING);
                            } catch (IOException e) {
                                logger.raw().warn("Failed to copy simulator log: " + e.getMessage());
                            }
                        }
                    });
                }

                // Run directory is ephemeral — clean up after capturing any interesting artifacts
                backend.cleanupAfterRun();

                if (runConfig.stopConditions().contains(StopCondition.ANY_FAIL)
                        && reportGenerator.hasFailures()
                        && earlyExit.compareAndSet(false, true)) {
                    logger.raw().warn("Assertion failure detected — stopping (--stop any-fail)");
                }
                if (runConfig.stopConditions().contains(StopCondition.ALL_PASS)
                        && runCount >= runConfig.stagnationLimit()
                        && reportGenerator.allPassed()
                        && earlyExit.compareAndSet(false, true)) {
                    logger.raw().info("All assertions passing — stopping (--stop all-pass)");
                }

                if (executionResult.isInteresting()) {
                    reportGenerator.generate(reportDir.resolve("report.json"));
                    boringRunStreak.set(0);
                } else if (boringRunStreak.incrementAndGet() >= runConfig.stagnationLimit()) {
                    return null;
                }

                if (runCount % PROGRESS_INTERVAL == 0) {
                    var counts = reportGenerator.passingCount();
                    logger.run("progress")
                            .with("run", runCount)
                            .with("stagnation", boringRunStreak.get())
                            .withPassing(counts[0], counts[1])
                            .log();
                }
            }
        } finally {
            if (persistentBackend != null) {
                persistentBackend.close();
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
                logger.run("check").withHash(plan.hash()).log();
                return new ExecutionPlan(plan, _ -> false);
            }
        }
        return orchestrator.nextPlan();
    }

    private ExecutionResult runOnce(ExecutionBackend backend, ExecutionPlan execution)
            throws IOException, InterruptedException {
        try {
            backend.startIteration(execution.plan());
            return monitorExecutionOutput(backend, execution);
        } finally {
            backend.afterIteration();
        }
    }

    private ExecutionResult monitorExecutionOutput(ExecutionBackend backend, ExecutionPlan execution)
            throws IOException, InterruptedException {
        var lastLogs = new ArrayDeque<String>();
        var result = new ExecutionResult();
        var logs = backend.logReader();
        for (var line = logs.readLine(); line != null; line = logs.readLine()) {
            if (lastLogs.size() >= 50) {
                lastLogs.removeFirst();
            }
            lastLogs.addLast(line);
            LogStatement log;
            SignalEvent event;
            if ((log = parseLog(line)) != null && (event = extractSignal(log)) != null) {
                boolean interestingEvent = execution.interesting().test(event);
                if (result.addSignal(event, interestingEvent)) {
                    return result;
                }
            }
        }
        // Log stream closed without "stopped" — synthesize a crash result
        int exitCode = backend.awaitCrash();
        result.synthesizeCrash(exitCode, lastLogs);
        logger.raw()
                .error("child process exited unexpectedly (code %d). Last %d log lines preserved in report."
                        .formatted(exitCode, lastLogs.size()));
        return result;
    }

    private LogStatement parseLog(String line) {
        if (runConfig.isDebugOrReplay()) {
            out.println(line);
        }
        if (isJson(line)) {
            try {
                return JSON_MAPPER.readValue(line, LogStatement.class);
            } catch (JacksonException e) {
                if (!runConfig.isDebugOrReplay()) {
                    logger.raw().debug(line);
                }
            }
        }
        return null;
    }

    private static boolean isJson(String line) {
        return line.length() > 2 && line.charAt(0) == '{' && line.charAt(line.length() - 1) == '}';
    }

    private SignalEvent extractSignal(LogStatement log) {
        try {
            return new SignalEvent(log.iteration(), JSON_MAPPER.treeToValue(log.log(), Signal.class));
        } catch (JacksonException e) {
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
        // --patch-module allows Thread subclasses to run as virtual threads by injecting
        // SimulatorThread (extends non-final VirtualThread) into java.base.
        command.add("--patch-module");
        command.add("java.base=%s".formatted(jvm.patchModuleJarPath()));
        command.addAll(List.of(
                "-javaagent:%s".formatted(jvm.agentJarPath()),
                "-Djava.io.tmpdir=%s".formatted(runTmpDir),
                "-D%s=%s".formatted(APPS_DIR_PROPERTY, jvm.instrumentedAppsDir())));
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
