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

import java.time.Instant;

/**
 * A streaming observer for simulation log events.
 *
 * <p>Implement this interface on your DST test class to observe log output from all simulated nodes
 * in real time. The runner automatically detects implementations and wires them into the simulation.
 *
 * <p><b>Important:</b> The {@link #process(Log)} method is invoked <em>outside</em> the simulation
 * context to protect determinism. Implementations must not share mutable state with the simulation
 * code running inside {@code run()}.
 *
 * <p>Example usage:
 * <pre>{@code
 * public class MyDST implements TraceAuditor {
 *     public void run() {
 *         Simulator.startNode("server", "10.0.0.1", this::server);
 *     }
 *
 *     @Override
 *     public void process(Log log) throws Throwable {
 *         if (log.message().contains("ERROR")) {
 *             throw new AssertionError("Unexpected error: " + log.message());
 *         }
 *     }
 * }
 * }</pre>
 */
public interface TraceAuditor {

    /**
     * A log entry emitted by a simulated node.
     *
     * @param host      the hostname of the node that produced the log
     * @param time      the simulated time at which the log was emitted
     * @param iteration the simulation iteration number
     * @param message   the log message text
     */
    record Log(String host, Instant time, long iteration, String message) {}

    /**
     * Called for each log entry emitted by simulated nodes.
     *
     * <p>Throwing any exception (typically {@link AssertionError}) immediately stops the simulation
     * and marks the run as a failure. The orchestrator saves the exact plan for deterministic replay.
     *
     * @param log the log entry to process
     * @throws Throwable if the log indicates a property violation
     */
    void process(Log log) throws Throwable;
}
