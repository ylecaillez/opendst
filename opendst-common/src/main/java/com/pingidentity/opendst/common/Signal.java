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
 */
public sealed interface Signal {

    /** Discriminator field written to JSON. The runner dispatches on this value. */
    String type();

    /** Human-readable label associated with the signal. */
    String message();

    // ── Application output ────────────────────────────────────────────────────

    /** Stdout line captured from a virtual host. */
    record ConsoleSignal(String message) implements Signal {
        @Override
        public String type() {
            return "stdout";
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
    record AssertSignal(AssertType kind, String message, boolean condition, Map<String, Object> details)
            implements Signal {
        @Override
        public String type() {
            return "assert";
        }
    }

    /**
     * Guidance data emitted alongside comparative assertions (e.g. {@code alwaysLessThan}).
     *
     * <p>Carries the {@code left} and {@code right} operand values so the planner
     * can compute how far the assertion is from being violated and steer exploration
     * toward narrower distances.
     */
    record GuidanceSignal(String message, Map<String, Object> guidance) implements Signal {

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

        @Override
        public String type() {
            return "guidance";
        }
    }

    // ── Fault injection ───────────────────────────────────────────────────────

    /** Notification that a fault was injected into the simulation. */
    record FaultSignal(String message) implements Signal {
        @Override
        public String type() {
            return "fault";
        }
    }

    // ── Lifecycle: simulator framework events ─────────────────────────────────
    //
    // Each lifecycle event has its own record so the runner can dispatch by
    // instanceof. Some events are diagnostic-only (the runner ignores them);
    // they exist as types so the agent can construct them and emit them with
    // typed serialization (no hand-rolled JsonGenerator).

    /** Simulation main loop entered. */
    record StartedSignal() implements Signal {
        @Override
        public String type() {
            return "started";
        }

        @Override
        public String message() {
            return "started";
        }
    }

    /** A segment boundary completed cleanly; carries the deterministic state hash. */
    record SegmentCompletedSignal(int hash) implements Signal {
        @Override
        public String type() {
            return "segment-completed";
        }

        @Override
        public String message() {
            return "segment-completed";
        }
    }

    /** Simulation finished cleanly; carries the final deterministic state hash. */
    record StoppedSignal(int hash) implements Signal {
        @Override
        public String type() {
            return "stopped";
        }

        @Override
        public String message() {
            return "stopped";
        }
    }

    /** Replay diverged from the recorded plan; the run is non-deterministic. */
    record NonDeterminismSignal(int expectedHash, int actualHash) implements Signal {
        @Override
        public String type() {
            return "non-determinism";
        }

        @Override
        public String message() {
            return "non-determinism detected";
        }
    }

    /** Simulator framework caught an internal error; the simulation is aborted. */
    record InternalErrorSignal(String cause, List<String> stacktrace) implements Signal {
        @Override
        public String type() {
            return "internal-error";
        }

        @Override
        public String message() {
            return "internal error";
        }
    }

    /** A user-supplied {@code TraceAuditor} threw; signaled once per run. */
    record TraceAuditorExceptionSignal(String cause, List<String> stacktrace) implements Signal {
        @Override
        public String type() {
            return "trace-auditor-exception";
        }

        @Override
        public String message() {
            return "trace auditor exception";
        }
    }

    /**
     * A virtual host's user code raised an uncaught exception.
     *
     * @param vhost     the virtual host whose code threw
     * @param thread    the simulator thread name
     * @param exception arbitrary Throwable POJO captured for diagnostics
     */
    record UncaughtExceptionSignal(String vhost, String thread, Object exception) implements Signal {
        @Override
        public String type() {
            return "uncaught-exception";
        }

        @Override
        public String message() {
            return "uncaught exception";
        }
    }

    /**
     * The simulator detected a platform (non-virtual) thread starting from inside a virtual host.
     * Diagnostic-only; the runner ignores it.
     */
    record PlatformThreadStartedSignal(String vhost, String threadName, String threadClass, String caller)
            implements Signal {
        @Override
        public String type() {
            return "platform-thread-started";
        }

        @Override
        public String message() {
            return "platform thread started";
        }
    }

    /**
     * A virtual host registered a JVM shutdown hook that the simulator skipped.
     * Diagnostic-only; the runner ignores it.
     */
    record PlatformThreadShutdownHookSkippedSignal(String vhost, String hookClass, String hookName) implements Signal {
        @Override
        public String type() {
            return "platform-thread-shutdown-hook-skipped";
        }

        @Override
        public String message() {
            return "platform thread shutdown hook skipped";
        }
    }
}
