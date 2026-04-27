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

import java.util.Map;

/**
 * Wire-format type for the structured signals emitted by the simulator during a run.
 *
 * <p>The agent serializes one of the five {@code Signal} subtypes per line to the child
 * JVM's {@code System.out} (wrapped in a log envelope), and the runner parses them back
 * to dispatch to {@link AssertSignal}, {@link LifecycleSignal}, etc.
 *
 * <p>The wire format intentionally avoids Jackson polymorphic-deserialization annotations
 * ({@code @JsonTypeInfo} / {@code @JsonSubTypes}) so that both ends can use
 * {@code jackson-jr}. The runner dispatches on the {@link #type()} discriminator field
 * by hand. This keeps both the agent and the runner free of {@code jackson-databind}.
 */
public sealed interface Signal
        permits Signal.ConsoleSignal,
                Signal.AssertSignal,
                Signal.GuidanceSignal,
                Signal.FaultSignal,
                Signal.LifecycleSignal {

    /** Discriminator field written to JSON. The runner dispatches on this value. */
    String type();

    /** Human-readable label associated with the signal. */
    String message();

    /** Stdout line captured from a virtual host. */
    record ConsoleSignal(String message) implements Signal {
        @Override
        public String type() {
            return "stdout";
        }
    }

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

    /**
     * Lifecycle event from the simulator framework: {@code started}, {@code segment-completed},
     * {@code stopped}, {@code uncaught exception}, etc.
     *
     * @param message      the lifecycle event name
     * @param hash         the deterministic state hash (used by {@code stopped} and {@code segment-completed})
     * @param cause        the failure cause for error events ({@code uncaught exception},
     *                     {@code internal error}, etc.), or {@code null}
     * @param expectedHash the expected hash for non-determinism reports, or {@code 0}
     * @param actualHash   the actual hash for non-determinism reports, or {@code 0}
     */
    record LifecycleSignal(String message, int hash, String cause, int expectedHash, int actualHash) implements Signal {
        @Override
        public String type() {
            return "lifecycle";
        }
    }

    /** Notification that a fault was injected into the simulation. */
    record FaultSignal(String message) implements Signal {
        @Override
        public String type() {
            return "fault";
        }
    }
}
