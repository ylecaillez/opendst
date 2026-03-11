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

/**
 * Immutable record holding all global simulation services.
 *
 * <p>Created once by {@link Simulator} after all components are constructed,
 * then shared (read-only) with {@link Node} instances. All fields are final —
 * no late-binding or volatile access needed.
 */
record SimulationContext(
        Simulator simulator,
        Time.Scheduler scheduler,
        Randomness.Source random,
        Faults.Config faults,
        StateHasher hasher,
        Network network,
        Faults.Injector faultInjector,
        ConsoleCapture logger) {}
