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

import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.util.Map.entry;
import static java.util.concurrent.ThreadLocalRandom.current;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.net.BindException;
import java.net.InetAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Fault injection for deterministic simulations.
 *
 * <p>This class contains the fault {@link Config configuration model} (carried inside the
 * {@link Plan}) and the {@link Injector} that applies faults at runtime. Currently only
 * network faults are implemented.
 *
 * <h3>Fault lifecycle</h3>
 *
 * <p>No faults are injected until the simulation calls {@link com.pingidentity.opendst.sdk.Signals#ready()}.
 * This lets services complete their bootstrap (bind ports, establish connections) before chaos
 * begins. Every hook in {@link Injector} is guarded by {@code simulator.isReady()}.
 *
 * <h3>Configuration flow</h3>
 *
 * <p>The fault configuration originates in the Maven plugin ({@code <faults>} in the POM or
 * defaults in {@code BuildMojo}), is baked into {@code build-config.json} inside the
 * self-contained JAR, converted to a {@link Config} by {@code BuildRunner.toFaultsConfig()},
 * serialized into each {@link Plan}, and deserialized in the child JVM by the {@link Simulator}
 * constructor.
 */
public final class Faults {

    /**
     * Fault injection configuration, carried inside the {@link Plan}.
     *
     * <p>The no-arg constructor creates a configuration with faults <b>disabled</b>. When the
     * plan does not include a fault config ({@code plan.faults() == null}), the simulator falls
     * back to this disabled default.
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
         * {@link Injector#halfLatency()}. The distribution is bimodal:
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
         * "clogging" latency in [0, {@code cloggingLatencyMaximum}] is assigned. This latency
         * is added on top of the base latency for every packet on that link, simulating
         * persistent congestion. The clogging value is directional (A&rarr;B may differ from
         * B&rarr;A) and sticky (subsequent connections between the same pair reuse it).
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
         * @param enabled                    master switch; when {@code false}, no network faults
         *                                   are injected
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

    /**
     * Applies fault injection during a simulation run.
     *
     * <p>The injector is called from {@link Node} (bind) and {@link NodeSocketImpl}
     * (connect, accept, send, receive). Every hook is guarded by
     * {@code simulator.isReady()} — no faults fire before
     * {@link com.pingidentity.opendst.sdk.Signals#ready()} is called.
     *
     * <p>The {@code clogPairLatency} map tracks per-direction congestion latency between
     * host pairs. It is populated on first connection (connect or accept) and reused for
     * all subsequent connections on the same directional link.
     */
    static final class Injector {
        private final Simulator simulator;
        private final Faults.Config faults;
        private final Map<Entry<InetAddress, InetAddress>, Duration> clogPairLatency = new HashMap<>();

        Injector(Simulator simulator, Faults.Config faults) {
            this.simulator = simulator;
            this.faults = faults;
        }

        /**
         * Hook called before a network bind operation. May throw {@link BindException} to
         * simulate an address-already-in-use failure when {@code SO_REUSEADDR} is not set
         * (5% probability).
         *
         * @param reuseAddress whether {@code SO_REUSEADDR} is enabled on the socket
         */
        void onNetworkBind(boolean reuseAddress) throws BindException {
            if (!reuseAddress
                    && simulator.isReady()
                    && faults.network().enabled()
                    && current().nextDouble() < 0.05) {
                throw new BindException("OpenDST network-address-reuse");
            }
        }

        /**
         * Hook called before a network send operation. May throw {@link SocketException} to
         * simulate a connection reset (probability: {@code connectionResetProbability}).
         */
        void onNetworkSend() throws SocketException {
            if (simulator.isReady() && faults.network().enabled()) {
                if (current().nextDouble() < faults.network().connectionResetProbability()) {
                    throw new SocketException("Connection reset");
                }
            }
        }

        /**
         * Hook called before a network receive operation. May throw {@link SocketException} to
         * simulate a connection reset (probability: {@code connectionResetProbability}).
         */
        void onNetworkReceive() throws SocketException {
            if (simulator.isReady() && faults.network().enabled()) {
                if (current().nextDouble() < faults.network().connectionResetProbability()) {
                    throw new SocketException("Connection reset");
                }
            }
        }

        /**
         * Returns a timeout duration to inject on this operation. Returns {@link Duration#ZERO}
         * if no timeout should be injected (probability: {@code timeoutProbability}).
         */
        Duration onNetworkTimeout() {
            if (simulator.isReady() && faults.network().enabled()) {
                if (current().nextDouble() < faults.network().timeoutProbability()) {
                    return ofMillis(10_000);
                }
            }
            return ZERO;
        }

        /**
         * Sets the clogging latency for a directional link ({@code from}&rarr;{@code to}) if not
         * already set. Called during connect and accept to assign persistent per-pair congestion.
         * Returns the effective latency (existing value if already set, or the new one).
         */
        Duration setPairLatencyIfNotSet(InetAddress from, InetAddress to, Duration duration) {
            var alreadySet = clogPairLatency.putIfAbsent(entry(from, to), duration);
            return alreadySet != null ? alreadySet : duration;
        }

        /**
         * Computes the simulated one-way delay for a send from {@code from} to {@code to}.
         * Loopback connections ({@code stableConnection = true}) get 1/10th of the base latency.
         * Normal connections get {@code halfLatency() + clogPairLatency}.
         */
        Duration networkSendDelay(InetAddress from, InetAddress to, boolean stableConnection) {
            if (stableConnection) {
                return ofNanos(halfLatency().toNanos() / 10);
            }
            var halfLatency = halfLatency();
            return halfLatency.plus(maxOptional(clogPairLatency.get(entry(from, to))));
        }

        /**
         * Computes the simulated one-way delay for a receive from {@code from} to {@code to}.
         * Loopback connections ({@code stableConnection = true}) get 1/10th of the base latency.
         * Normal connections get {@code halfLatency() + clogPairLatency}.
         */
        Duration networkReceiveDelay(InetAddress from, InetAddress to, boolean stableConnection) {
            if (stableConnection) {
                return ofNanos(halfLatency().toNanos() / 10);
            }
            var pair = entry(from, to);
            var halfLatency = halfLatency();
            return halfLatency.plus(maxOptional(clogPairLatency.get(pair)));
        }

        private Duration maxOptional(Duration durationOrNull) {
            return durationOrNull != null ? ZERO.compareTo(durationOrNull) > 0 ? ZERO : durationOrNull : ZERO;
        }

        /**
         * Samples a one-way latency from a bimodal distribution.
         *
         * <ul>
         *   <li><b>Fast path (99.9%)</b> — picks from
         *       [{@code networkLatencyMinimum}, {@code networkLatencyFast}] using inverse
         *       scaling, producing a right-skewed distribution where most values cluster
         *       near the minimum.</li>
         *   <li><b>Slow path (0.1%)</b> — picks from
         *       [{@code networkLatencyMinimum}, {@code networkLatencySlow}] using linear
         *       interpolation, modeling rare high-latency spikes.</li>
         * </ul>
         *
         * <p>Called separately for send and receive; the total round-trip latency for a
         * packet is the sum of two independent samples.
         */
        private Duration halfLatency() {
            var config = faults.network();
            long precision = SECONDS.toNanos(1);
            long a = current().nextLong(precision);
            long probabilityFastNanos = (long) (0.999 * precision);
            if (a <= probabilityFastNanos) {
                long scaledA = Math.max(1, (a * precision) / probabilityFastNanos);
                return ofNanos((config.networkLatencyMinimum().toNanos() * (precision - scaledA)
                                + (config.networkLatencyFast().toNanos() * precision / scaledA))
                        / (2 * probabilityFastNanos));
            }
            long scaledA = ((a - probabilityFastNanos) * precision) / (precision - probabilityFastNanos);
            return ofNanos(((config.networkLatencyMinimum().toNanos() * (precision - scaledA))
                            + (config.networkLatencySlow().toNanos() * scaledA))
                    / (2 * precision));
        }
    }

    private Faults() {
        // Prevent instantiation
    }
}
