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
import java.util.Set;
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

    record CheckpointLine(String type, String id, long iteration, long nextBoundary, int hash) {}

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
    // Per-fork: each fork keeps its own replay queue so it only verifies plans it ran itself.
    // With multiple nyx VMs, JIT state differs between VMs, so cross-VM replay produces
    // false non-determinism reports.
    private static final int PAST_PLANS_CAPACITY = 10;
    private final AtomicBoolean earlyExit = new AtomicBoolean();

    /**
     * A checkpoint known to this fork's shim, paired with the completed prefix of the run that
     * produced it.  {@code prefix} contains every segment (seed + until, no hash) whose boundary
     * had been crossed when the checkpoint was taken, in order from iteration 0.
     *
     * <p>{@code checkpoint.nextBoundary()} is the {@code until} of the segment that was
     * in-progress at snapshot time (= the guest's frozen {@code nextIteration}). It is used
     * both to drive the shim's {@code resume_segment_idx} and to validate that the candidate
     * plan contains the same in-progress segment.
     *
     * <p>Before injection we verify that the candidate plan's leading segments match this prefix
     * (same seed and until for each position) and that the next segment's {@code until} matches
     * {@code nextBoundary}. A checkpoint from a run with a different seed sequence or a different
     * in-progress segment boundary would carry a different accumulated hasher state and cause
     * spurious segment-hash mismatches inside the simulation.
     */
     private record LocalCheckpoint(Plan.Checkpoint checkpoint, List<Plan.Segment> prefix) {
        /** Returns true when {@code plan}'s leading segments are compatible with this prefix.
         *  The prefix includes the in-progress segment (seed + until == nextBoundary), so both
         *  completed and in-progress seeds are verified. This ensures the RNG sequence in the
         *  snapshot matches the plan exactly, so fork and nyx-lite reproduce the same hash. */
        boolean isCompatibleWith(Plan plan) {
            var planSegments = plan.segments();
            if (prefix.size() >= planSegments.size()) return false;
            for (int i = 0; i < prefix.size(); i++) {
                var p = prefix.get(i);
                var s = planSegments.get(i);
                if (p.seed() != s.seed() || p.until() != s.until()) return false;
            }
            return true;
        }
    }

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
        var pastPlans = new java.util.ArrayDeque<Plan>(PAST_PLANS_CAPACITY);
        // Local checkpoint registry: all checkpoints seen by this fork's shim, paired with the
        // completed prefix (segments from iteration 0 up to the checkpoint, seed+until only) of
        // the run that produced them.  Before injection the prefix is verified against the
        // candidate plan so that only path-compatible checkpoints are used — a checkpoint from a
        // run with a different seed sequence would carry a different accumulated hasher state and
        // cause spurious segment-hash mismatches inside the simulation.
        var localCheckpoints = new ArrayList<LocalCheckpoint>();
        // Exploration credit: accumulates (1 - replayProbability) per run. When >= 1 an exploration
        // run is forced, guaranteeing the exact explore/verify ratio without coin-flip starvation.
        double explorationCredit = 1.0; // start with 1 so the very first run is always an exploration

        // For nyx backend: create the persistent backend once per fork thread and reuse it.
        // For fork backend: backendFactory is null — a fresh ForkBackend is created per iteration.
        ExecutionBackend persistentBackend = backendFactory != null ? backendFactory.get() : null;

        try {
            for (; ; ) {
                if (earlyExit.get()) {
                    return null;
                }
                explorationCredit += 1.0 - runConfig.replayProbability();
                ExecutionPlan executionPlan =
                        getNewExecutionPlanOrReplay(pastPlans, localCheckpoints, explorationCredit);
                if (executionPlan.plan().hash() == 0) {
                    // This run is an exploration — consume one unit of credit
                    explorationCredit -= 1.0;
                }
                // Always feed observed checkpoints into the local registry, paired with the
                // completed prefix of this run so that later injection can verify path compatibility.
                final var localSink = executionPlan.checkpointSink();
                final var runPlan = executionPlan.plan();
                executionPlan = new ExecutionPlan(runPlan, executionPlan.interesting(), cp -> {
                    // Collect the prefix: all segments up to AND INCLUDING the in-progress segment
                    // (until == nextBoundary). Including the in-progress segment's seed in the prefix
                    // ensures isCompatibleWith rejects plans that use a different seed for that segment,
                    // which would produce a different hash even though the checkpoint iteration matches.
                    long nextBoundary = cp.nextBoundary() > 0 ? cp.nextBoundary() : cp.iteration();
                    var prefix = runPlan.segments().stream()
                            .filter(s -> s.until() <= nextBoundary)
                            .map(s -> new Plan.Segment(s.seed(), s.until()))
                            .toList();
                    localCheckpoints.add(new LocalCheckpoint(cp, prefix));
                    localSink.accept(cp);
                });
                int runCount = runSequence.incrementAndGet();

                // Nyx: reuse persistent backend; Fork: create fresh backend each iteration
                ExecutionBackend backend;
                if (persistentBackend != null) {
                    if (!persistentBackend.isHealthy()) {
                        // Shim crashed — abort only this fork, let others continue
                        logger.raw().error("nyx-lite shim is no longer healthy — aborting fork");
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

                // Detect infrastructure crashes (child JVM dies before the simulator starts)
                // and shim crashes (VM or shim dies mid-simulation). Both are transient faults
                // that should not be recorded as assertion failures or trigger --stop any-fail.
                if (executionResult.isInfrastructureCrash() || executionResult.isShimCrash()) {
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
                    // Don't treat as an assertion failure — just retry
                    backend.cleanupAfterRun();
                    continue;
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
                    var lastCp = executionResult.checkpoints().isEmpty()
                            ? null
                            : executionResult.checkpoints().getLast();
                    // Full-replay verify: record this plan so a later run can replay and confirm
                    // the hash. Plans with a checkpoint are included — since isCompatibleWith now
                    // verifies the in-progress segment seed, fork running all segments from scratch
                    // produces the same hash as the nyx-lite checkpoint-assisted run.
                    if (pastPlans.size() >= PAST_PLANS_CAPACITY) {
                        pastPlans.poll();
                    }
                    pastPlans.add(executionPlan
                            .plan()
                            .withSegmentHashes(executionResult.segmentHashByIteration())
                            .withHash(executionResult.runHash()));
                    // Snapshot-verify: restore from checkpoint and check the final hash matches.
                    // Only meaningful for the nyx backend where the snapshot cache persists.
                    if (lastCp != null && persistentBackend != null) {
                        if (pastPlans.size() >= PAST_PLANS_CAPACITY) {
                            pastPlans.poll();
                        }
                        pastPlans.add(executionPlan
                                .plan()
                                .withCheckpoint(
                                        new Plan.Checkpoint(lastCp.id(), lastCp.iteration(), lastCp.nextBoundary()))
                                .withSegmentHashes(executionResult.segmentHashByIteration())
                                .withHash(executionResult.runHash()));
                        // Save the exploration log so that if a partial-verify later diverges
                        // we have both the original and verify logs available for diffing.
                        var explorationHexHash = HexFormat.of().toHexDigits(executionResult.runHash());
                        saveLog(
                                backend,
                                reportDir.resolve("plans").resolve(explorationHexHash + ".exploration.log.json"));
                    }
                } else {
                    var kind = executionPlan.plan().checkpoint() != null ? "partial" : "full";
                    if (executionPlan.plan().hash() == executionResult.runHash()) {
                        logger.run("verified")
                                .with("kind", kind)
                                .withHash(executionResult.runHash())
                                .log();
                    } else {
                        reportGenerator.recordDeterminismFailure();
                        var failLog =
                                logger.run("fail").error().with("kind", kind).withHash(executionResult.runHash());
                        // Find the first segment where the observed hash diverges from the
                        // expected hash recorded in the plan. This pinpoints which segment
                        // introduced the non-determinism.
                        //
                        // For partial (checkpoint-assisted) verify runs, segments whose
                        // until <= checkpoint.iteration() were not executed — skip those to
                        // avoid false positives where actual=0 simply because they were skipped.
                        var planSegments = executionPlan.plan().segments();
                        var checkpoint = executionPlan.plan().checkpoint();
                        long checkpointIteration = checkpoint != null ? checkpoint.iteration() : -1;
                        var actualHashes = executionResult.segmentHashByIteration();
                        long nonZeroPlanSegHashes = planSegments.stream()
                                .filter(s -> s.hash() != 0 && s.until() > checkpointIteration)
                                .count();
                        failLog = failLog.with(
                                        "expectedHash",
                                        Integer.toHexString(executionPlan.plan().hash()))
                                .with("cpIteration", checkpointIteration)
                                .with("planSegHashes", nonZeroPlanSegHashes);
                        for (var seg : planSegments) {
                            if (seg.hash() == 0) continue; // hash not recorded for this segment
                            if (seg.until() <= checkpointIteration) continue; // not executed in verify
                            int actual = actualHashes.getOrDefault(seg.until(), 0);
                            if (seg.hash() != actual) {
                                failLog = failLog.withIteration(seg.until())
                                        .with("expectedSegHash", Integer.toHexString(seg.hash()))
                                        .with("actualSegHash", Integer.toHexString(actual));
                                break;
                            }
                        }
                        failLog.log();
                        // Save the verify log keyed by the expected hash so it can be diffed
                        // against the exploration log (<expectedHash>.exploration.log.json).
                        var expectedHexHash =
                                HexFormat.of().toHexDigits(executionPlan.plan().hash());
                        saveLog(backend, reportDir.resolve("plans").resolve(expectedHexHash + ".verify-fail.log.json"));
                    }
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

                boolean isVerifyRun = executionPlan.plan().hash() != 0;
                if (executionResult.isInteresting()) {
                    reportGenerator.generate(reportDir.resolve("report.json"));
                    boringRunStreak.set(0);
                } else if (!isVerifyRun && boringRunStreak.incrementAndGet() >= runConfig.stagnationLimit()) {
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

    /**
     * Copies the simulator log to {@code dest}, overwriting any existing file.
     * Silently skips if the source log does not exist.
     */
    private void saveLog(ExecutionBackend backend, Path dest) {
        backend.simulatorLogPath().ifPresent(src -> {
            if (exists(src)) {
                try {
                    copy(src, dest, REPLACE_EXISTING);
                } catch (IOException e) {
                    logger.raw().warn("Failed to save simulator log to " + dest + ": " + e.getMessage());
                }
            }
        });
    }

    private ExecutionPlan getNewExecutionPlanOrReplay(
            java.util.ArrayDeque<Plan> pastPlans, List<LocalCheckpoint> localCheckpoints, double explorationCredit) {
        if (explorationCredit < 1.0 && !pastPlans.isEmpty()) {
            int idx = current().nextInt(pastPlans.size());
            var plan = pastPlans.stream().skip(idx).findFirst().orElseThrow();
            var kind = plan.checkpoint() != null ? "partial" : "full";
            logger.run("check").with("kind", kind).withHash(plan.hash()).log();
            return new ExecutionPlan(plan, _ -> false);
        }
        var executionPlan = orchestrator.nextPlan();
        // Substitute the best path-compatible locally-cached checkpoint into explore plans.
        // "Path-compatible" means the checkpoint's recorded prefix (seed+until for each completed
        // segment) matches the leading segments of the candidate plan.  An incompatible checkpoint
        // would carry a different accumulated hasher state, causing spurious hash mismatches inside
        // the simulation.  The orchestrator emits plans with checkpoint=null; each fork injects its
        // own checkpoint so that only UUIDs known to this fork's shim are used.
        if (executionPlan.plan().checkpoint() == null && !localCheckpoints.isEmpty()) {
            long branchPoint = executionPlan.plan().segments().getLast().until();
            LocalCheckpoint best = null;
            for (var lc : localCheckpoints) {
                if (lc.checkpoint().iteration() < branchPoint && lc.isCompatibleWith(executionPlan.plan())) {
                    if (best == null
                            || lc.checkpoint().iteration() > best.checkpoint().iteration()) {
                        best = lc;
                    }
                }
            }
            if (best != null) {
                // The shim uses nextBoundary to find resume_segment_idx on restore.
                // nextBoundary = the until of the in-progress segment at snapshot time
                // (= the guest's frozen nextIteration). Pass it in Plan.Checkpoint so
                // the shim can skip past the in-progress segment correctly.
                var plan = executionPlan.plan();
                var enriched = plan.withCheckpoint(new Plan.Checkpoint(
                        best.checkpoint().id(),
                        best.checkpoint().iteration(),
                        best.checkpoint().nextBoundary()));
                return new ExecutionPlan(enriched, executionPlan.interesting(), executionPlan.checkpointSink());
            }
        }
        return executionPlan;
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
            if ("SHIM_DONE".equals(line)) {
                // Shim signals end-of-iteration; VM may have shut down without emitting "stopped"
                if (backend instanceof NyxBackend nyxBackend) {
                    nyxBackend.markShimDoneSeen();
                }
                return result;
            }
            if (lastLogs.size() >= 50) {
                lastLogs.removeFirst();
            }
            lastLogs.addLast(line);
            var cp = parseCheckpoint(line);
            if (cp != null) {
                result.addCheckpoint(cp);
                execution.checkpointSink().accept(new Plan.Checkpoint(cp.id(), cp.iteration(), cp.nextBoundary()));
                continue;
            }
            LogStatement log;
            SignalEvent event;
            if ((log = parseLog(line)) != null && (event = extractSignal(log)) != null) {
                boolean interestingEvent = execution.interesting().test(event);
                if (result.addSignal(event, interestingEvent)) {
                    return result;
                }
            }
        }
        // Log stream closed (shim exited) without SHIM_DONE — synthesize a crash result
        int exitCode = backend.awaitCrash();
        result.synthesizeCrash(exitCode, lastLogs);
        logger.raw()
                .error("child process exited unexpectedly (code %d). Last %d log lines preserved in report."
                        .formatted(exitCode, lastLogs.size()));
        return result;
    }

    private ExecutionResult.ObservedCheckpoint parseCheckpoint(String line) {
        if (!isJson(line) || !line.contains("\"type\":\"checkpoint\"")) return null;
        try {
            var cp = JSON_MAPPER.readValue(line, CheckpointLine.class);
            if (!"checkpoint".equals(cp.type()) || cp.id() == null) return null;
            return new ExecutionResult.ObservedCheckpoint(cp.id(), cp.iteration(), cp.nextBoundary(), cp.hash());
        } catch (JacksonException e) {
            return null;
        }
    }

    private LogStatement parseLog(String line) {
        if (isJson(line)) {
            try {
                var stmt = JSON_MAPPER.readValue(line, LogStatement.class);
                if (runConfig.isDebugOrReplay() || runConfig.forkCount() == 1) {
                    out.println(line);
                }
                return stmt;
            } catch (JacksonException e) {
                logger.raw().debug(line);
            }
        } else {
            logger.raw().debug(line);
        }
        return null;
    }

    private static boolean isJson(String line) {
        return line.length() > 2 && line.charAt(0) == '{' && line.charAt(line.length() - 1) == '}';
    }

    private SignalEvent extractSignal(LogStatement log) {
        try {
            var signal = JSON_MAPPER.treeToValue(log.log(), Signal.class);
            if (signal instanceof Signal.AssertSignal as && as.message() == null) {
                logger.raw().warn("assert signal with null message — raw log: " + log.log());
            }
            return new SignalEvent(log.iteration(), signal);
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
