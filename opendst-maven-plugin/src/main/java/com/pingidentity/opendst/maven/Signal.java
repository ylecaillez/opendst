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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pingidentity.opendst.maven.Signal.AssertSignal;
import com.pingidentity.opendst.maven.Signal.FaultSignal;
import com.pingidentity.opendst.maven.Signal.LifecycleSignal;
import java.util.Map;

/**
 * Represents a signal emitted by the simulator.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Signal.AssertSignal.class, name = "assert"),
    @JsonSubTypes.Type(value = Signal.LifecycleSignal.class, name = "lifecycle"),
    @JsonSubTypes.Type(value = Signal.FaultSignal.class, name = "fault")
})
public sealed interface Signal permits AssertSignal, FaultSignal, LifecycleSignal {
    SignalType type();

    String message();

    String fullJson();

    enum SignalType {
        LIFECYCLE("lifecycle"),
        ASSERT("assert"),
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

    record AssertSignal(
            AssertType kind, String message, boolean condition, Map<String, Object> guidance, String fullJson)
            implements Signal {

        /**
         * Returns the distance-to-violation for comparative assertions, or {@code NaN} if not applicable.
         *
         * <p>For comparative assertions, guidance contains {@code left} and {@code right} values.
         * The distance is {@code |left - right|}, representing how far the assertion is from being violated.
         * A smaller distance means the system is closer to a counter-example.
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

        public enum AssertType {
            ALWAYS("always"),
            ALWAYS_OR_UNREACHABLE("alwaysOrUnreachable"),
            SOMETIMES("sometimes");

            private final String value;

            AssertType(String value) {
                this.value = value;
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

    record LifecycleSignal(String message, String reason, int hash, String fullJson) implements Signal {

        @Override
        public SignalType type() {
            return SignalType.LIFECYCLE;
        }
    }

    record FaultSignal(String message, String fullJson) implements Signal {

        @Override
        public SignalType type() {
            return SignalType.FAULT;
        }
    }
}
