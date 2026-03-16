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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pingidentity.opendst.runner.Signal.AssertSignal;
import com.pingidentity.opendst.runner.Signal.AssertSignal.AssertType;
import com.pingidentity.opendst.runner.Signal.ConsoleSignal;
import com.pingidentity.opendst.runner.Signal.FaultSignal;
import com.pingidentity.opendst.runner.Signal.GuidanceSignal;
import com.pingidentity.opendst.runner.Signal.LifecycleSignal;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Represents a structured signal emitted by the simulator during a run.
 *
 * <p>Signals are deserialized from the child JVM's JSON stdout stream and dispatched
 * by the parent orchestrator. The five subtypes cover lifecycle events (start/stop),
 * assertion outcomes, guidance data for comparative assertions, console output,
 * and fault injection notices.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Signal.LifecycleSignal.class, name = "lifecycle"),
    @JsonSubTypes.Type(value = Signal.AssertSignal.class, name = "assert"),
    @JsonSubTypes.Type(value = Signal.GuidanceSignal.class, name = "guidance"),
    @JsonSubTypes.Type(value = Signal.ConsoleSignal.class, name = "stdout"),
    @JsonSubTypes.Type(value = Signal.FaultSignal.class, name = "fault")
})
public sealed interface Signal permits ConsoleSignal, AssertSignal, GuidanceSignal, FaultSignal, LifecycleSignal {
    SignalType type();

    String message();

    enum SignalType {
        LIFECYCLE("lifecycle"),
        ASSERT("assert"),
        GUIDANCE("guidance"),
        CONSOLE("stdout"),
        FAULT("fault");

        private final String value;

        SignalType(String value) {
            this.value = value;
        }

        @JsonCreator
        public static SignalType fromString(String value) {
            for (var type : values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown signal type: " + value);
        }

        @Override
        @com.fasterxml.jackson.annotation.JsonValue
        public String toString() {
            return value;
        }
    }

    record ConsoleSignal(AssertType kind, String message) implements Signal {
        @Override
        public SignalType type() {
            return SignalType.CONSOLE;
        }
    }

    record AssertSignal(AssertType kind, String message, boolean condition, JsonNode details) implements Signal {

        public enum AssertType {
            ALWAYS("always", true),
            ALWAYS_OR_UNREACHABLE("alwaysOrUnreachable", false),
            SOMETIMES("sometimes", true);

            private final String value;
            private final boolean mustHit;

            AssertType(String value, boolean mustHit) {
                this.value = value;
                this.mustHit = mustHit;
            }

            boolean mustHit() {
                return mustHit;
            }

            @JsonCreator
            public static AssertType fromString(String value) {
                for (var type : values()) {
                    if (type.value.equals(value)) {
                        return type;
                    }
                }
                throw new IllegalArgumentException("Unknown assert type: " + value);
            }

            @Override
            @com.fasterxml.jackson.annotation.JsonValue
            public String toString() {
                return value;
            }
        }

        @Override
        public SignalType type() {
            return SignalType.ASSERT;
        }
    }

    /**
     * Guidance data emitted alongside comparative assertions (e.g. {@code alwaysLessThan}).
     *
     * <p>Carries the {@code left} and {@code right} operand values so the orchestrator
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
        double distanceToViolation() {
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
        public SignalType type() {
            return SignalType.GUIDANCE;
        }
    }

    record LifecycleSignal(String message, String reason, int hash, String cause) implements Signal {

        @Override
        public SignalType type() {
            return SignalType.LIFECYCLE;
        }
    }

    record FaultSignal(String message) implements Signal {

        @Override
        public SignalType type() {
            return SignalType.FAULT;
        }
    }
}
