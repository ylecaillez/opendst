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
package com.pingidentity.opendst.sdk;

import static com.pingidentity.opendst.common.AssertType.ALWAYS;
import static com.pingidentity.opendst.common.AssertType.ALWAYS_OR_UNREACHABLE;
import static com.pingidentity.opendst.common.AssertType.SOMETIMES;
import static com.pingidentity.opendst.simulator.Node.currentNodeOrThrow;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

import com.pingidentity.opendst.common.AssertType;
import com.pingidentity.opendst.common.Signal.AssertSignal;
import com.pingidentity.opendst.common.Signal.GuidanceSignal;
import com.pingidentity.opendst.intercept.Intercepts;
import java.util.Map;

/**
 * Build-time redirect target for {@link Assert}. Each public static method here mirrors the
 * SDK signature; instrumented call-sites are rewritten from {@code Assert.x(...)} to
 * {@code AssertImpl.x(...)} by {@link com.pingidentity.opendst.maven.Instrumentation}.
 */
@Intercepts("com.pingidentity.opendst.sdk.Assert")
public final class AssertImpl {

    // --- "Always" Properties ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#always(boolean,String,java.util.Map)")
    public static void always(boolean condition, String message, Map<String, Object> details) {
        recordAssert(ALWAYS, message, condition, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#always(boolean,String)")
    public static void always(boolean condition, String message) {
        always(condition, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysOrUnreachable(boolean,String,java.util.Map)")
    public static void alwaysOrUnreachable(boolean condition, String message, Map<String, Object> details) {
        recordAssert(ALWAYS_OR_UNREACHABLE, message, condition, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysOrUnreachable(boolean,String)")
    public static void alwaysOrUnreachable(boolean condition, String message) {
        alwaysOrUnreachable(condition, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#unreachable(String,java.util.Map)")
    public static void unreachable(String message, Map<String, Object> details) {
        alwaysOrUnreachable(false, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#unreachable(String)")
    public static void unreachable(String message) {
        unreachable(message, null);
    }

    // --- "Sometimes" Properties ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimes(boolean,String,java.util.Map)")
    public static void sometimes(boolean condition, String message, Map<String, Object> details) {
        recordAssert(SOMETIMES, message, condition, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimes(boolean,String)")
    public static void sometimes(boolean condition, String message) {
        sometimes(condition, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#reachable(String,java.util.Map)")
    public static void reachable(String message, Map<String, Object> details) {
        sometimes(true, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#reachable(String)")
    public static void reachable(String message) {
        reachable(message, null);
    }

    // --- Comparative ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysGreaterThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysGreaterThan(
            T left, T right, String message, Map<String, Object> details) {
        comparative(ALWAYS, left.compareTo(right) > 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysGreaterThan(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void alwaysGreaterThan(T left, T right, String message) {
        alwaysGreaterThan(left, right, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysGreaterThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysGreaterThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        comparative(ALWAYS, left.compareTo(right) >= 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysGreaterThanOrEqualTo(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void alwaysGreaterThanOrEqualTo(T left, T right, String message) {
        alwaysGreaterThanOrEqualTo(left, right, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysLessThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysLessThan(
            T left, T right, String message, Map<String, Object> details) {
        comparative(ALWAYS, left.compareTo(right) < 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysLessThan(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void alwaysLessThan(T left, T right, String message) {
        alwaysLessThan(left, right, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysLessThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void alwaysLessThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        comparative(ALWAYS, left.compareTo(right) <= 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysLessThanOrEqualTo(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void alwaysLessThanOrEqualTo(T left, T right, String message) {
        alwaysLessThanOrEqualTo(left, right, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesGreaterThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesGreaterThan(
            T left, T right, String message, Map<String, Object> details) {
        comparative(SOMETIMES, left.compareTo(right) > 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesGreaterThan(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void sometimesGreaterThan(T left, T right, String message) {
        sometimesGreaterThan(left, right, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesGreaterThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesGreaterThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        comparative(SOMETIMES, left.compareTo(right) >= 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesGreaterThanOrEqualTo(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void sometimesGreaterThanOrEqualTo(
            T left, T right, String message) {
        sometimesGreaterThanOrEqualTo(left, right, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesLessThan(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesLessThan(
            T left, T right, String message, Map<String, Object> details) {
        comparative(SOMETIMES, left.compareTo(right) < 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesLessThan(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void sometimesLessThan(T left, T right, String message) {
        sometimesLessThan(left, right, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesLessThanOrEqualTo(Number,Number,String,java.util.Map)")
    public static <T extends Number & Comparable<T>> void sometimesLessThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {
        comparative(SOMETIMES, left.compareTo(right) <= 0, left, right, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesLessThanOrEqualTo(Number,Number,String)")
    public static <T extends Number & Comparable<T>> void sometimesLessThanOrEqualTo(T left, T right, String message) {
        sometimesLessThanOrEqualTo(left, right, message, null);
    }

    // --- Grouped ---

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysSome(java.util.Map,String,java.util.Map)")
    public static void alwaysSome(Map<String, Boolean> conditions, String message, Map<String, Object> details) {
        grouped(ALWAYS, conditions.values().stream().anyMatch(b -> b), conditions, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#alwaysSome(java.util.Map,String)")
    public static void alwaysSome(Map<String, Boolean> conditions, String message) {
        alwaysSome(conditions, message, null);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesAll(java.util.Map,String,java.util.Map)")
    public static void sometimesAll(Map<String, Boolean> conditions, String message, Map<String, Object> details) {
        grouped(SOMETIMES, conditions.values().stream().allMatch(b -> b), conditions, message, details);
    }

    @Intercepts("com.pingidentity.opendst.sdk.Assert#sometimesAll(java.util.Map,String)")
    public static void sometimesAll(Map<String, Boolean> conditions, String message) {
        sometimesAll(conditions, message, null);
    }

    private static void comparative(
            AssertType kind,
            boolean condition,
            Number left,
            Number right,
            String message,
            Map<String, Object> details) {
        recordAssert(kind, message, condition, details);
        recordGuidance(message, of("left", left, "right", right));
    }

    private static void grouped(
            AssertType kind,
            boolean condition,
            Map<String, Boolean> conditions,
            String message,
            Map<String, Object> details) {
        recordAssert(kind, message, condition, details);
        recordGuidance(message, of("conditions", conditions));
    }

    private static void recordAssert(AssertType kind, String message, boolean condition, Map<String, Object> details) {
        currentNodeOrThrow().log(new AssertSignal(kind, requireNonNull(message), condition, details));
    }

    private static void recordGuidance(String message, Map<String, Object> guidance) {
        currentNodeOrThrow().log(new GuidanceSignal(requireNonNull(message), guidance));
    }

    private AssertImpl() {
        // Prevent instantiation
    }
}
