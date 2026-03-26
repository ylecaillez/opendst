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
package com.pingidentity.opendst.sdk;

import java.util.Map;

/**
 * Provides methods to assert properties and guide state-space exploration.
 * Aligned with the Antithesis SDK model.
 */
public final class Assert {

    private Assert() {}

    // --- "Always" Properties (Invariant checks) ---

    /**
     * Asserts that a condition is always true whenever this function is called,
     * AND that it is called at least once.
     *
     * @param condition the condition to check
     * @param message   a descriptive label for the assertion
     * @param details   optional key-value pairs providing additional context
     */
    public static void always(boolean condition, String message, Map<String, Object> details) {}

    /**
     * Asserts that a condition is always true whenever this function is called,
     * AND that it is called at least once.
     *
     * @param condition the condition to check
     * @param message   a descriptive label for the assertion
     */
    public static void always(boolean condition, String message) {}

    /**
     * Asserts that a condition is always true whenever this function is called.
     * The assertion passes even if it's never called.
     *
     * @param condition the condition to check
     * @param message   a descriptive label for the assertion
     * @param details   optional key-value pairs providing additional context
     */
    public static void alwaysOrUnreachable(boolean condition, String message, Map<String, Object> details) {}

    /**
     * Asserts that a condition is always true whenever this function is called.
     * The assertion passes even if it's never called.
     *
     * @param condition the condition to check
     * @param message   a descriptive label for the assertion
     */
    public static void alwaysOrUnreachable(boolean condition, String message) {}

    /**
     * Asserts that the line of code is never reached.
     *
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static void unreachable(String message, Map<String, Object> details) {}

    /**
     * Asserts that the line of code is never reached.
     *
     * @param message a descriptive label for the assertion
     */
    public static void unreachable(String message) {}

    // --- "Sometimes" Properties (Liveness checks) ---

    /**
     * Asserts that a condition is true at least once during the simulation session.
     *
     * @param condition the condition to check
     * @param message   a descriptive label for the assertion
     * @param details   optional key-value pairs providing additional context
     */
    public static void sometimes(boolean condition, String message, Map<String, Object> details) {}

    /**
     * Asserts that a condition is true at least once during the simulation session.
     *
     * @param condition the condition to check
     * @param message   a descriptive label for the assertion
     */
    public static void sometimes(boolean condition, String message) {}

    /**
     * Asserts that the line of code is reached at least once during the simulation session.
     *
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static void reachable(String message, Map<String, Object> details) {}

    /**
     * Asserts that the line of code is reached at least once during the simulation session.
     *
     * @param message a descriptive label for the assertion
     */
    public static void reachable(String message) {}

    // --- Comparative Assertions ---

    /**
     * Asserts that {@code left} is always strictly greater than {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void alwaysGreaterThan(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is always strictly greater than {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void alwaysGreaterThan(T left, T right, String message) {}

    /**
     * Asserts that {@code left} is always greater than or equal to {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void alwaysGreaterThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is always greater than or equal to {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void alwaysGreaterThanOrEqualTo(T left, T right, String message) {}

    /**
     * Asserts that {@code left} is always strictly less than {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void alwaysLessThan(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is always strictly less than {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void alwaysLessThan(T left, T right, String message) {}

    /**
     * Asserts that {@code left} is always less than or equal to {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void alwaysLessThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is always less than or equal to {@code right} whenever this function is called.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void alwaysLessThanOrEqualTo(T left, T right, String message) {}

    /**
     * Asserts that {@code left} is greater than {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void sometimesGreaterThan(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is greater than {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void sometimesGreaterThan(T left, T right, String message) {}

    /**
     * Asserts that {@code left} is greater than or equal to {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void sometimesGreaterThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is greater than or equal to {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void sometimesGreaterThanOrEqualTo(
            T left, T right, String message) {}

    /**
     * Asserts that {@code left} is strictly less than {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void sometimesLessThan(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is strictly less than {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void sometimesLessThan(T left, T right, String message) {}

    /**
     * Asserts that {@code left} is less than or equal to {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     * @param details optional key-value pairs providing additional context
     */
    public static <T extends Number & Comparable<T>> void sometimesLessThanOrEqualTo(
            T left, T right, String message, Map<String, Object> details) {}

    /**
     * Asserts that {@code left} is less than or equal to {@code right} at least once during the simulation session.
     *
     * @param <T>     a numeric, comparable type
     * @param left    the left-hand operand
     * @param right   the right-hand operand
     * @param message a descriptive label for the assertion
     */
    public static <T extends Number & Comparable<T>> void sometimesLessThanOrEqualTo(T left, T right, String message) {}

    // --- Grouped Assertions ---

    /**
     * Similar to always(x || y || ...), but provides individual proposition visibility.
     *
     * @param conditions a map of proposition names to their boolean values
     * @param message    a descriptive label for the assertion
     * @param details    optional key-value pairs providing additional context
     */
    public static void alwaysSome(Map<String, Boolean> conditions, String message, Map<String, Object> details) {}

    /**
     * Similar to always(x || y || ...), but provides individual proposition visibility.
     *
     * @param conditions a map of proposition names to their boolean values
     * @param message    a descriptive label for the assertion
     */
    public static void alwaysSome(Map<String, Boolean> conditions, String message) {}

    /**
     * Similar to sometimes(x &amp;&amp; y &amp;&amp; ...), but provides individual proposition visibility.
     *
     * @param conditions a map of proposition names to their boolean values
     * @param message    a descriptive label for the assertion
     * @param details    optional key-value pairs providing additional context
     */
    public static void sometimesAll(Map<String, Boolean> conditions, String message, Map<String, Object> details) {}

    /**
     * Similar to sometimes(x &amp;&amp; y &amp;&amp; ...), but provides individual proposition visibility.
     *
     * @param conditions a map of proposition names to their boolean values
     * @param message    a descriptive label for the assertion
     */
    public static void sometimesAll(Map<String, Boolean> conditions, String message) {}
}
