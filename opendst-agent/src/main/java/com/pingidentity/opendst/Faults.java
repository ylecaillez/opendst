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
import static java.time.Duration.between;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.time.Instant.EPOCH;
import static java.time.Instant.now;
import static java.util.Arrays.asList;
import static java.util.Map.entry;
import static java.util.concurrent.ThreadLocalRandom.current;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * Functional module for fault simulation and configuration.
 */
public final class Faults {

    /**
     * Configuration for fault injection.
     */
    public record Config(NetworkConfig network, FileSystemConfig fileSystem) {

        public String summary() {
            var summary = new StringJoiner(", ");
            if (network.enabled()) {
                var net = new StringJoiner("/", "net:", "");
                if (network.cloggingProbability() > 0) net.add("clogging");
                summary.add(net.toString());
            }
            if (fileSystem.enabled()) {
                if (fileSystem.ioErrorProbability() > 0) summary.add("fs:io-error");
            }
            return summary.length() > 0 ? summary.toString() : "none";
        }

        public Config() {
            this(new NetworkConfig(), new FileSystemConfig());
        }

        public record NetworkConfig(
                boolean enabled,

                Duration networkLatencyMinimum,
                Duration networkLatencyFast,
                Duration networkLatencySlow,

                double cloggingProbability,

                Duration cloggingLatencyMaximum) {

            public NetworkConfig() {
                this(false,
                     // Latency
                     ofNanos(100_000),
                     ofNanos(800_000),
                     ofMillis(100),

                     // Clogging
                     0.5,
                     ofMillis(100));
            }
        }

        public record FileSystemConfig(boolean enabled, double ioErrorProbability) {

            public FileSystemConfig() {
                this(false, 0.0);
            }
        }
    }

    /**
     * Handles modular fault injection during simulation.
     */
    static final class Injector {
        public static final String FAULT_INJECTION = "fault injection";
        private final Simulator simulator;
        private final Faults.Config faults;
        private final Map<InetAddress, Instant> clogSendUntil = new HashMap<>();
        private final Map<InetAddress, Instant> clogReceiveUntil = new HashMap<>();
        private final Map<Entry<InetAddress, InetAddress>, Instant> clogPairUntil = new HashMap<>();
        private final Map<Entry<InetAddress, InetAddress>, Duration> clogPairLatency = new HashMap<>();
        private final Map<Entry<InetAddress, InetAddress>, Instant> disconnectPairUntil = new HashMap<>();
        private final Map<Thread, Entry<Instant, Duration>> slowThreadUntil = new WeakHashMap<>();

        private int faultIdSeq;

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
                    && current().nextInt(1000) == 0) {
                throw new BindException("OpenDST network-address-reuse");
            }
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
            var now = now();
            var halfLatency = halfLatency();
            return between(
                    now,
                    latest(
                            now.plus(halfLatency.plus(maxOptional(clogPairLatency.get(pair)))),
                            clogPairUntil.getOrDefault(pair, EPOCH),
                            clogSendUntil.getOrDefault(from, EPOCH),
                            clogReceiveUntil.getOrDefault(to, EPOCH)));
        }

        private Duration maxOptional(Duration durationOrNull) {
            return durationOrNull != null ? ZERO.compareTo(durationOrNull) > 0 ? ZERO : durationOrNull : ZERO;
        }

        private Instant latest(Instant... instants) {
            return Collections.max(asList(instants));
        }

        boolean isDisconnected(InetAddress from, InetAddress to) {
            return disconnectPairUntil.getOrDefault(entry(from, to), EPOCH).isAfter(now());
        }

        private Duration halfLatency() {
            var config = faults.network();
            long precision = SECONDS.toNanos(1);
            long a = current().nextLong(precision);
            long probabilityFastNanos = (long) (0.999 * precision);
            if (a <= probabilityFastNanos) {
                long scaledA = (a * precision) / probabilityFastNanos;
                return ofNanos((config.networkLatencyMinimum().toNanos() * (precision - scaledA)
                        + (config.networkLatencyFast().toNanos() / scaledA)) / (2 * probabilityFastNanos));
            }
            long scaledA = (a - probabilityFastNanos) / (precision - probabilityFastNanos);
            return ofNanos(((config.networkLatencyMinimum().toNanos() * (precision - scaledA))
                    + (config.networkLatencySlow().toNanos() * scaledA))
                                   / (2 * precision));
        }

        /**
         * Hook called before a filesystem operation.
         *
         * @throws IOException if a filesystem fault occurs
         */
        void onFileSystemOp() throws IOException {
            var config = faults.fileSystem();
            if (simulator.isReady()
                    && config.enabled()
                    && config.ioErrorProbability() > 0
                    && current().nextDouble() < config.ioErrorProbability()) {
                throw new IOException("OpenDST: filesystem-fault");
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent;
    }

    private Faults() {
        // Prevent instantiation
    }
}
