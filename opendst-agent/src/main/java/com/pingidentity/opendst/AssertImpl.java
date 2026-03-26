/*
 * Copyright 2024-2026 Ping Identity Corporation
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
package com.pingidentity.opendst;

import static com.pingidentity.opendst.Node.currentNodeOrThrow;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

import java.util.Map;

/**
 * Implementation of the OpenDST Assert SDK.
 */
@Intercepts("com.pingidentity.opendst.sdk.Assert")
public final class AssertImpl {

    // --- "Always" Properties ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#always(boolean,String,java.util.Map)")
    public static void always(boolean condition, String message, Map<String, Object> details) {
        log("always", message, condition, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysOrUnreachable(boolean,String,java.util.Map)")
    public static void alwaysOrUnreachable(boolean condition, String message, Map<String, Object> details) {
        log("alwaysOrUnreachable", message, condition, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#unreachable(String,java.util.Map)")
    public static void unreachable(String message, Map<String, Object> details) {
        alwaysOrUnreachable(false, message, details);
    }

    // --- "Sometimes" Properties ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimes(boolean,String,java.util.Map)")
    public static void sometimes(boolean condition, String message, Map<String, Object> details) {
        log("sometimes", message, condition, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#reachable(String,java.util.Map)")
    public static void reachable(String message, Map<String, Object> details) {
        sometimes(true, message, details);
    }

    // --- Comparative ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysGreaterThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysGreaterThan(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        boolean condition = left.compareTo(right) > 0;
        log(node, "always", message, condition, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysGreaterThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysGreaterThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        boolean condition = left.compareTo(right) >= 0;
        log(node, "always", message, condition, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysLessThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysLessThan(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        boolean condition = left.compareTo(right) < 0;
        log(node, "always", message, condition, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysLessThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysLessThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        boolean condition = left.compareTo(right) <= 0;
        log(node, "always", message, condition, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesGreaterThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesGreaterThan(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        log(node, "sometimes", message, left.compareTo(right) > 0, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesGreaterThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesGreaterThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        log(node, "sometimes", message, left.compareTo(right) >= 0, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesLessThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesLessThan(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        log(node, "sometimes", message, left.compareTo(right) < 0, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesLessThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesLessThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        log(node, "sometimes", message, left.compareTo(right) <= 0, details);
        logGuidance(node, message, of("left", left, "right", right));
    }

    // --- Grouped ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysSome(java.util.Map,String,java.util.Map)")
    public static void alwaysSome(Map<String, Boolean> conditions, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        boolean overall = conditions.values().stream().anyMatch(b -> b);
        log(node, "always", message, overall, details);
        logGuidance(node, message, of("conditions", conditions));
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesAll(java.util.Map,String,java.util.Map)")
    public static void sometimesAll(Map<String, Boolean> conditions, String message, Map<String, Object> details) {
        var node = currentNodeOrThrow();
        boolean overall = conditions.values().stream().allMatch(b -> b);
        log(node, "sometimes", message, overall, details);
        logGuidance(node, message, of("conditions", conditions));
    }

    /** Emits an assertion verdict — "condition was true/false". */
    private static void log(String kind, String message, boolean condition, Map<String, Object> details) {
        log(currentNodeOrThrow(), kind, message, condition, details);
    }

    private static void log(Node node, String kind, String message, boolean condition, Map<String, Object> details) {
        currentNodeOrThrow()
                .logger()
                .logAssert(
                        requireNonNull(message),
                        node.hostName,
                        node.simulator().instant(),
                        node.random().iteration())
                .withString("kind", kind)
                .withBoolean("condition", condition)
                .withPOJO("details", details)
                .log();
    }

    /** Emits a guidance signal — "how close to violation". Does not affect the deterministic hash. */
    private static void logGuidance(Node node, String message, Map<String, Object> guidance) {
        node.logger()
                .logGuidance(
                        requireNonNull(message),
                        node.hostName,
                        node.simulator().instant(),
                        node.random().iteration())
                .withPOJO("guidance", guidance)
                .log();
    }

    private AssertImpl() {
        // Prevent instantiation
    }
}
