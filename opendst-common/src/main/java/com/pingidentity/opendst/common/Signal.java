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
package com.pingidentity.opendst.common;

import java.util.List;
import java.util.Map;

/**
 * Wire-format type for the structured signals emitted by the simulator during a run.
 *
 * <p>The agent serializes one {@code Signal} per line to the child JVM's {@code System.out}
 * (wrapped in a log envelope), and the runner parses each line back into the corresponding
 * subtype.
 *
 * <p>The wire format intentionally avoids Jackson polymorphic-deserialization annotations
 * ({@code @JsonTypeInfo} / {@code @JsonSubTypes}) so that both ends can use
 * {@code jackson-jr}. The runner dispatches on the {@link #type()} discriminator field
 * by hand. Lifecycle events are split into one subtype per event so the runner can dispatch
 * by {@code instanceof} rather than by string matching on a {@code message} field.
 *
 * <p>{@code type} and {@code message} are required record components on every subtype (declared
 * first, so they appear first in the JSON preamble). Subtypes whose discriminator/message are
 * constants expose a convenience constructor that defaults them — keeping callsites concise
 * while letting the reflective serializer in the agent walk record components uniformly.
 */
public sealed interface Signal {

    /** Discriminator field written to JSON. The runner dispatches on this value. */
    String type();

    /** Human-readable label associated with the signal. */
    String message();

    // ── Application output ────────────────────────────────────────────────────

    /** Stdout line captured from a virtual host. */
    record ConsoleSignal(String type, String message) implements Signal {
        public ConsoleSignal(String message) {
            this("stdout", message);
        }
    }

    // ── Assertion outcomes ────────────────────────────────────────────────────

    /**
     * Outcome of an assertion evaluation.
     *
     * @param kind      the assertion kind ({@code ALWAYS}, {@code SOMETIMES}, {@code ALWAYS_OR_UNREACHABLE})
     * @param message   the human-readable assertion message
     * @param condition the boolean verdict for this evaluation
     * @param details   arbitrary diagnostic data captured at the assertion call site, or {@code null}
     */
    record AssertSignal(String type, String message, AssertType kind, boolean condition, Map<String, Object> details)
            implements Signal {
        public AssertSignal(AssertType kind, String message, boolean condition, Map<String, Object> details) {
            this("assert", message, kind, condition, details);
        }
    }

    /**
     * Guidance data emitted alongside comparative assertions (e.g. {@code alwaysLessThan}).
     *
     * <p>Carries the {@code left} and {@code right} operand values so the planner
     * can compute how far the assertion is from being violated and steer exploration
     * toward narrower distances.
     */
    record GuidanceSignal(String type, String message, Map<String, Object> guidance) implements Signal {
        public GuidanceSignal(String message, Map<String, Object> guidance) {
            this("guidance", message, guidance);
        }

        /**
         * Returns the distance-to-violation, or {@code NaN} if not applicable.
         *
         * <p>The distance is {@code |left - right|}, representing how far the assertion
         * is from being violated. A smaller distance means the system is closer to
         * a counter-example.
         */
        public double distanceToViolation() {
            if (guidance == null) {
                return Double.NaN;
            }
            var left = guidance.get("left");
            var right = guidance.get("right");
            if (left instanceof Number l && right instanceof Number r) {
                return Math.abs(l.doubleValue() - r.doubleValue());
            }
            return Double.NaN;
        }
    }

    // ── Fault injection ───────────────────────────────────────────────────────

    /** Notification that a fault was injected into the simulation. */
    record FaultSignal(String type, String message) implements Signal {
        public FaultSignal(String message) {
            this("fault", message);
        }
    }

    // ── Lifecycle: simulator framework events ─────────────────────────────────
    //
    // Each lifecycle event has its own record so the runner can dispatch by
    // instanceof. Some events are diagnostic-only (the runner ignores them);
    // they exist as types so the agent can construct them and emit them with
    // typed serialization (no hand-rolled JsonGenerator).

    /** Simulation main loop entered. */
    record StartedSignal(String type, String message) implements Signal {
        public StartedSignal() {
            this("started", "started");
        }
    }

    /** A segment boundary completed cleanly; carries the deterministic state hash. */
    record SegmentCompletedSignal(String type, String message, int hash) implements Signal {
        public SegmentCompletedSignal(int hash) {
            this("segment-completed", "segment-completed", hash);
        }
    }

    /** Simulation finished cleanly; carries the final deterministic state hash. */
    record StoppedSignal(String type, String message, int hash) implements Signal {
        public StoppedSignal(int hash) {
            this("stopped", "stopped", hash);
        }
    }

    /** Replay diverged from the recorded plan; the run is non-deterministic. */
    record NonDeterminismSignal(String type, String message, int expectedHash, int actualHash) implements Signal {
        public NonDeterminismSignal(int expectedHash, int actualHash) {
            this("non-determinism", "non-determinism detected", expectedHash, actualHash);
        }
    }

    /** Simulator framework caught an internal error; the simulation is aborted. */
    record InternalErrorSignal(String type, String message, String cause, List<String> stacktrace) implements Signal {
        public InternalErrorSignal(String cause, List<String> stacktrace) {
            this("internal-error", "internal error", cause, stacktrace);
        }
    }

    /** A user-supplied {@code TraceAuditor} threw; signaled once per run. */
    record TraceAuditorExceptionSignal(String type, String message, String cause, List<String> stacktrace)
            implements Signal {
        public TraceAuditorExceptionSignal(String cause, List<String> stacktrace) {
            this("trace-auditor-exception", "trace auditor exception", cause, stacktrace);
        }
    }

    /**
     * A virtual host's user code raised an uncaught exception.
     *
     * @param thread    the simulator thread name
     * @param exception arbitrary Throwable POJO captured for diagnostics
     */
    record UncaughtExceptionSignal(String type, String message, String thread, Object exception) implements Signal {
        public UncaughtExceptionSignal(String thread, Object exception) {
            this("uncaught-exception", "uncaught exception", thread, exception);
        }
    }

    /**
     * The simulator detected a platform (non-virtual) thread starting from inside a virtual host.
     * Diagnostic-only; the runner ignores it.
     */
    record PlatformThreadStartedSignal(
            String type, String message, String threadName, String threadClass, String caller) implements Signal {
        public PlatformThreadStartedSignal(String threadName, String threadClass, String caller) {
            this("platform-thread-started", "platform thread started", threadName, threadClass, caller);
        }
    }

    /**
     * A virtual host registered a JVM shutdown hook that the simulator skipped.
     * Diagnostic-only; the runner ignores it.
     */
    record PlatformThreadShutdownHookSkippedSignal(String type, String message, String hookClass, String hookName)
            implements Signal {
        public PlatformThreadShutdownHookSkippedSignal(String hookClass, String hookName) {
            this("platform-thread-shutdown-hook-skipped", "platform thread shutdown hook skipped", hookClass, hookName);
        }
    }
}
