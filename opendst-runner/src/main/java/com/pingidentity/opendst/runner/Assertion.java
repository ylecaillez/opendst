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

import static com.pingidentity.opendst.runner.Signal.AssertSignal.AssertType.ALWAYS;
import static com.pingidentity.opendst.runner.Signal.AssertSignal.AssertType.ALWAYS_OR_UNREACHABLE;

import com.pingidentity.opendst.runner.Signal.AssertSignal.AssertType;

/** Represents a property found in the bytecode. */
public record Assertion(AssertType kind, String message, String origin, int line) {

    /** Built-in assertion that passes when the simulation emits a {@code lifecycle/started} signal. */
    public static final Assertion SIMULATION_STARTED =
            new Assertion(ALWAYS, "simulation started", "opendst:built-in", 0);

    /** Built-in assertion that passes when the simulation emits a {@code lifecycle/stopped} signal. */
    public static final Assertion SIMULATION_TERMINATED =
            new Assertion(ALWAYS, "simulation terminated", "opendst:built-in", 0);

    /** Built-in assertion that fails when an uncaught exception occurs in user code. */
    public static final Assertion NO_UNCAUGHT_EXCEPTION =
            new Assertion(ALWAYS_OR_UNREACHABLE, "no uncaught exception", "opendst:built-in", 0);

    /** Built-in assertion that fails when the TraceAuditor's processLogs() throws. */
    public static final Assertion NO_TRACE_AUDITOR_EXCEPTION =
            new Assertion(ALWAYS_OR_UNREACHABLE, "no exception thrown in trace auditor", "opendst:built-in", 0);

    /** Built-in assertion that fails on internal errors or non-determinism. */
    public static final Assertion NO_INTERNAL_ERROR =
            new Assertion(ALWAYS_OR_UNREACHABLE, "no internal error", "opendst:built-in", 0);
}
