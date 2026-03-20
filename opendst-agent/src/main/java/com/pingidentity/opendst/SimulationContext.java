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
        TimeInterceptors.Scheduler scheduler,
        RandomInterceptors.Source random,
        Faults.Config faults,
        NetworkInterceptors network,
        Faults.Injector faultInjector,
        ConsoleCapture logger) {

    /** Maximum number of simulated nodes. */
    static final int MAX_NODES = 100;

    /** Maximum number of IP address-to-hostname mappings. */
    static final int MAX_ADDRESSES = 256;

    /** Maximum number of simultaneously queued scheduled tasks. */
    static final int MAX_TASKS = 10_000;

    /** Maximum number of virtual threads per node. */
    static final int MAX_VIRTUAL_THREADS_PER_NODE = 1_000;
}
