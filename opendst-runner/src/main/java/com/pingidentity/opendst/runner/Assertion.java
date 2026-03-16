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

import com.pingidentity.opendst.runner.Signal.AssertSignal.AssertType;

/** Represents a property found in the bytecode. */
public record Assertion(AssertType kind, String message, String origin, int line) {

    /** Built-in assertion that passes when the simulation emits a {@code lifecycle/started} signal. */
    public static final Assertion SIMULATION_STARTED =
            new Assertion(ALWAYS, "simulation started", "opendst:built-in", 0);

    /** Built-in assertion that passes when the simulation stops with {@code reason=success}. */
    public static final Assertion SIMULATION_STOPPED_SUCCESSFULLY =
            new Assertion(ALWAYS, "simulation stopped successfully", "opendst:built-in", 0);
}
