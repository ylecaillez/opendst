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
 * Functional module for fault simulation and configuration.
 */
public final class Faults {

    /**
     * Configuration for fault injection.
     */
    public record Config(NetworkConfig network) {

        public Config() {
            this(new NetworkConfig());
        }

        public record NetworkConfig(
                boolean enabled,

                Duration networkLatencyMinimum,
                Duration networkLatencyFast,
                Duration networkLatencySlow,

                double cloggingProbability,

                Duration cloggingLatencyMaximum,

                double connectionResetProbability,
                double timeoutProbability) {

            public NetworkConfig() {
                this(
                        false,
                        // Latency
                        ofNanos(100_000),
                        ofNanos(800_000),
                        ofMillis(100),

                        // Clogging
                        0.5,
                        ofMillis(100),

                        // Reset & Timeout
                        0.001,
                        0.001);
            }
        }
    }

    /**
     * Handles modular fault injection during simulation.
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
         * Hook called before a network bind operation.
         *
         * @param reuseAddress whether SO_REUSEADDR is enabled
         */
        void onNetworkBind(boolean reuseAddress) throws BindException {
            if (!reuseAddress
                    && simulator.isReady()
                    && faults.network().enabled()
                    && current().nextDouble() < 0.001) {
                throw new BindException("OpenDST network-address-reuse");
            }
        }

        /**
         * Hook called before a network send operation.
         */
        void onNetworkSend() throws SocketException {
            if (simulator.isReady() && faults.network().enabled()) {
                if (current().nextDouble() < faults.network().connectionResetProbability()) {
                    throw new SocketException("Connection reset");
                }
            }
        }

        /**
         * Hook called before a network receive operation.
         */
        void onNetworkReceive() throws SocketException {
            if (simulator.isReady() && faults.network().enabled()) {
                if (current().nextDouble() < faults.network().connectionResetProbability()) {
                    throw new SocketException("Connection reset");
                }
            }
        }

        Duration onNetworkTimeout() {
            if (simulator.isReady() && faults.network().enabled()) {
                if (current().nextDouble() < faults.network().timeoutProbability()) {
                    return ofMillis(10_000);
                }
            }
            return ZERO;
        }

        Duration setPairLatencyIfNotSet(InetAddress from, InetAddress to, Duration duration) {
            var alreadySet = clogPairLatency.putIfAbsent(entry(from, to), duration);
            return alreadySet != null ? alreadySet : duration;
        }

        Duration networkSendDelay(InetAddress from, InetAddress to, boolean stableConnection) {
            return stableConnection ? halfLatency().dividedBy(10) : halfLatency();
        }

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

        boolean isDisconnected(InetAddress from, InetAddress to) {
            return false;
        }

        private Duration halfLatency() {
            var config = faults.network();
            long precision = SECONDS.toNanos(1);
            long a = current().nextLong(precision);
            long probabilityFastNanos = (long) (0.999 * precision);
            if (a <= probabilityFastNanos) {
                long scaledA = Math.max(1, (a * precision) / probabilityFastNanos);
                return ofNanos((config.networkLatencyMinimum().toNanos() * (precision - scaledA)
                                + (config.networkLatencyFast().toNanos() / scaledA))
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
