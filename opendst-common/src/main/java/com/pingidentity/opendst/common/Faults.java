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
package com.pingidentity.opendst.common;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;

import java.time.Duration;

/**
 * Fault injection configuration carried inside the {@link Plan}.
 *
 * <p>This module owns only the configuration model. The runtime injector that applies
 * faults during a simulation lives with the agent (see {@code FaultInjector} in
 * {@code opendst-agent}).
 *
 * <h3>Configuration flow</h3>
 *
 * <p>The fault configuration originates in the Maven plugin ({@code <faults>} in the POM
 * or defaults in {@code BuildMojo}), is baked into {@code build-config.json} inside the
 * self-contained JAR, converted to a {@link Config} by the runner, serialized into each
 * {@link Plan}, and deserialized in the child JVM by the simulator.
 */
public final class Faults {

    private Faults() {
        // Prevent instantiation
    }

    /**
     * Fault injection configuration, carried inside the {@link Plan}.
     *
     * <p>The no-arg constructor creates a configuration with faults <b>disabled</b>. When
     * the plan does not include a fault config ({@code plan.faults() == null}), the
     * simulator falls back to this disabled default.
     */
    public record Config(NetworkConfig network) {

        public Config() {
            this(new NetworkConfig());
        }

        /**
         * Network fault parameters.
         *
         * <h4>Latency model</h4>
         *
         * <p>Each send or receive operation incurs a simulated one-way delay computed by
         * the agent's fault injector. The distribution is bimodal:
         * <ul>
         *   <li>99.9% of samples fall in the <b>fast</b> range
         *       [{@code networkLatencyMinimum}, {@code networkLatencyFast}], with an
         *       inverse-scaled distribution that skews toward the minimum.</li>
         *   <li>0.1% of samples fall in the <b>slow</b> range
         *       [{@code networkLatencyMinimum}, {@code networkLatencySlow}], modeling
         *       occasional high-latency spikes.</li>
         * </ul>
         *
         * <h4>Clogging</h4>
         *
         * <p>When a connection is first established between two hosts, a random per-pair
         * "clogging" latency in [0, {@code cloggingLatencyMaximum}] is assigned. This
         * latency is added on top of the base latency for every packet on that link,
         * simulating persistent congestion. The clogging value is directional
         * (A&rarr;B may differ from B&rarr;A) and sticky (subsequent connections between
         * the same pair reuse it).
         *
         * <h4>Probabilistic failures</h4>
         *
         * <ul>
         *   <li>{@code connectionResetProbability} -- chance of throwing
         *       {@code SocketException("Connection reset")} on each send or receive.</li>
         *   <li>{@code timeoutProbability} -- chance of a socket timeout on each
         *       send or receive operation.</li>
         * </ul>
         *
         * @param enabled                    master switch; when {@code false}, no network
         *                                   faults are injected
         * @param networkLatencyMinimum      floor of the latency distribution
         * @param networkLatencyFast         ceiling for the fast (99.9%) latency range
         * @param networkLatencySlow         ceiling for the slow (0.1%) latency range
         * @param cloggingLatencyMaximum     upper bound for per-pair congestion latency
         * @param connectionResetProbability probability of a connection reset per send/receive
         * @param timeoutProbability         probability of a socket timeout per send/receive
         */
        public record NetworkConfig(
                boolean enabled,
                Duration networkLatencyMinimum,
                Duration networkLatencyFast,
                Duration networkLatencySlow,
                Duration cloggingLatencyMaximum,
                double connectionResetProbability,
                double timeoutProbability) {

            /** Creates a disabled network fault configuration with sensible defaults. */
            public NetworkConfig() {
                this(
                        false,
                        // Latency
                        ofNanos(100_000),
                        ofNanos(800_000),
                        ofMillis(100),

                        // Clogging
                        ofMillis(100),

                        // Reset & Timeout
                        0.001,
                        0.001);
            }
        }
    }
}
