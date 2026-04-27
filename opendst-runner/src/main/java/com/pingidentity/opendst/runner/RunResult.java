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

import static com.pingidentity.opendst.common.AssertType.ALWAYS;
import static com.pingidentity.opendst.common.AssertType.ALWAYS_OR_UNREACHABLE;
import static com.pingidentity.opendst.runner.RunResult.TrackedAssertion.newFailAssertion;
import static com.pingidentity.opendst.runner.RunResult.TrackedAssertion.newPassAssertion;

import com.pingidentity.opendst.common.AssertType;
import com.pingidentity.opendst.common.Signal.AssertSignal;
import com.pingidentity.opendst.common.Signal.LifecycleSignal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the outcome of a single simulation run, including assertion results,
 * per-segment hashes, and the final run hash used for flakiness detection.
 */
final class RunResult {

    record TrackedAssertion(
            AssertType kind,
            String name,
            int passCount,
            long firstPassIteration,
            Map<String, Object> firstPassDetails,
            int failCount,
            long firstFailIteration,
            Map<String, Object> firstFailDetails) {

        static TrackedAssertion newPassAssertion(
                AssertType kind, String name, long iteration, Map<String, Object> details) {
            return new TrackedAssertion(kind, name, 1, iteration, details, 0, -1, null);
        }

        static TrackedAssertion newFailAssertion(
                AssertType kind, String name, long iteration, Map<String, Object> details) {
            return new TrackedAssertion(kind, name, 0, -1, null, 1, iteration, details);
        }

        TrackedAssertion pass() {
            return new TrackedAssertion(
                    kind,
                    name,
                    passCount + 1,
                    firstPassIteration,
                    firstPassDetails,
                    failCount,
                    firstFailIteration,
                    firstFailDetails);
        }

        TrackedAssertion fail() {
            return new TrackedAssertion(
                    kind,
                    name,
                    passCount,
                    firstPassIteration,
                    firstPassDetails,
                    failCount + 1,
                    firstFailIteration,
                    firstFailDetails);
        }
    }

    private final Map<String, TrackedAssertion> assertionsHit = new HashMap<>();
    private final List<Integer> segmentHashes = new ArrayList<>();
    private boolean interesting;
    private int runHash;

    int runHash() {
        return runHash;
    }

    /** {@return the hashes captured at each segment boundary, in order of emission} */
    List<Integer> segmentHashes() {
        return segmentHashes;
    }

    boolean isInteresting() {
        return interesting || runFailed();
    }

    Map<String, TrackedAssertion> assertionsHit() {
        return assertionsHit;
    }

    /**
     * Returns {@code true} if any {@code ALWAYS} or {@code ALWAYS_OR_UNREACHABLE} assertion
     * has at least one failure. {@code SOMETIMES} failures are the expected complement side
     * of the condition and do not indicate a run-level violation.
     */
    boolean runFailed() {
        return assertionsHit.values().stream()
                .anyMatch(a -> a.failCount() > 0 && (a.kind() == ALWAYS || a.kind() == ALWAYS_OR_UNREACHABLE));
    }

    /**
     * Returns {@code true} if the child JVM crashed before the simulator started.
     *
     * <p>This indicates an infrastructure problem (bad classpath, missing class, OOM on
     * startup, etc.) rather than a simulation-level assertion failure. The distinction
     * matters because infrastructure crashes should trigger early abort rather than being
     * treated as interesting exploration results that reset the stagnation counter.
     */
    boolean isInfrastructureCrash() {
        var started = assertionsHit.get("simulation started");
        return runFailed() && (started == null || started.passCount() == 0);
    }

    /**
     * Records a synthetic internal error when the child JVM exits without sending
     * structured lifecycle signals (hard crash, pre-simulator failure, OOM, etc.).
     *
     * <p>Synthesizes a fail on {@code "no internal error"} with the exit code and
     * last captured log lines as details, plus a pass on {@code "simulation terminated"}
     * so that {@link #runFailed()} and {@link #isInteresting()} return {@code true}.
     */
    void synthesizeCrash(int exitCode, Collection<String> lastLogs) {
        var details = new LinkedHashMap<String, Object>();
        details.put("exitCode", exitCode);
        details.put("cause", "child process exited unexpectedly (code %d)".formatted(exitCode));
        details.put("lastLogs", List.copyOf(lastLogs));
        trackAssertion(ALWAYS_OR_UNREACHABLE, "no internal error", false, 0, details);
        trackAssertion(ALWAYS, "simulation terminated", true, 0, null);
    }

    boolean addSignal(SignalEvent signal, boolean isInteresting) {
        interesting |= isInteresting;
        if (signal.signal() instanceof LifecycleSignal lifecycleSignal) {
            switch (lifecycleSignal.message()) {
                case "started" -> trackAssertion(ALWAYS, "simulation started", true, signal.iteration(), null);
                case "segment-completed" -> segmentHashes.add(lifecycleSignal.hash());
                case "uncaught exception" ->
                    trackAssertion(
                            ALWAYS_OR_UNREACHABLE,
                            "no uncaught exception",
                            false,
                            signal.iteration(),
                            causeDetails(lifecycleSignal));
                case "trace auditor exception" ->
                    trackAssertion(
                            ALWAYS_OR_UNREACHABLE,
                            "no exception thrown in trace auditor",
                            false,
                            signal.iteration(),
                            causeDetails(lifecycleSignal));
                case "internal error" ->
                    trackAssertion(
                            ALWAYS_OR_UNREACHABLE,
                            "no internal error",
                            false,
                            signal.iteration(),
                            causeDetails(lifecycleSignal));
                case "non-determinism detected" ->
                    trackAssertion(
                            ALWAYS_OR_UNREACHABLE,
                            "no internal error",
                            false,
                            signal.iteration(),
                            nonDeterminismDetails(lifecycleSignal));
                case "stopped" -> {
                    trackAssertion(ALWAYS, "simulation terminated", true, signal.iteration(), null);
                    runHash = lifecycleSignal.hash();
                    return true;
                }
                default -> {
                    // Ignore unknown lifecycle messages
                }
            }
        } else if (signal.signal() instanceof AssertSignal assertSignal) {
            trackAssertion(
                    assertSignal.kind(),
                    assertSignal.message(),
                    assertSignal.condition(),
                    signal.iteration(),
                    assertSignal.details());
        }
        // GuidanceSignals are handled by the planner only; no tracking needed here.
        return false;
    }

    /**
     * Builds a details map for lifecycle signals that carry a cause message.
     */
    private static Map<String, Object> causeDetails(LifecycleSignal signal) {
        var node = new LinkedHashMap<String, Object>();
        if (signal.cause() != null) {
            node.put("cause", signal.cause());
        }
        return node;
    }

    /**
     * Builds a details map for non-determinism lifecycle signals,
     * including the expected and actual hash codes.
     */
    private static Map<String, Object> nonDeterminismDetails(LifecycleSignal signal) {
        var node = new LinkedHashMap<String, Object>();
        node.put("expectedHash", Integer.toHexString(signal.expectedHash()));
        node.put("actualHash", Integer.toHexString(signal.actualHash()));
        return node;
    }

    private void trackAssertion(
            AssertType kind, String name, boolean pass, long iteration, Map<String, Object> details) {
        assertionsHit.compute(
                name,
                (_, existing) -> existing == null
                        ? pass
                                ? newPassAssertion(kind, name, iteration, details)
                                : newFailAssertion(kind, name, iteration, details)
                        : pass ? existing.pass() : existing.fail());
    }
}
