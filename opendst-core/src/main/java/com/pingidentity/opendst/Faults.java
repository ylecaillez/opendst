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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.net.BindException;
import java.net.SocketException;
import java.util.HashSet;
import java.util.StringJoiner;
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
                if (network.latencyProbability() > 0) net.add("latency");
                if (network.partitionProbability() > 0) net.add("partition");
                if (network.packetLossProbability() > 0) net.add("loss");
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
                double latencyProbability,
                long minLatencyMs,
                long maxLatencyMs,
                double partitionProbability,
                long minPartitionDurationMs,
                long maxPartitionDurationMs,
                double packetLossProbability) {

            public NetworkConfig() {
                this(false, 0.0, 0, 0, 0.0, 0, 0, 0.0);
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
        private final SimulationContext context;
        private int faultIdSeq;

        Injector(SimulationContext context) {
            this.context = context;
        }

        /**
         * Hook called before a network bind operation.
         *
         * @param reuseAddress whether SO_REUSEADDR is enabled
         */
        void onNetworkBind(boolean reuseAddress) throws BindException {
            if (!reuseAddress
                    && context.simulator().isReady()
                    && context.faults().network().enabled()
                    && context.random().nextInt(1000) == 0) {
                throw new BindException("OpenDST network-address-reuse");
            }
        }

        /**
         * Hook called before a network write operation.
         *
         * @param from source host
         * @param to   destination host
         * @return latency to apply in milliseconds
         * @throws IOException if a network fault (like partition) occurs
         */
        long onNetworkWrite(String from, String to) throws IOException {
            var config = context.faults().network();
            if (!context.simulator().isReady() || !config.enabled()) {
                return 0;
            } else if (config.partitionProbability() > 0
                    && context.random().nextDouble() < config.partitionProbability()) {
                induceRandomPartition(config);
            }

            if (!context.network().canReach(from, to)) {
                throw new SocketException("OpenDST: network-partition-fault");
            } else if (config.packetLossProbability() > 0
                    && context.random().nextDouble() < config.packetLossProbability()) {
                throw new SocketException("OpenDST: network-fault");
            } else if (config.latencyProbability() > 0 && context.random().nextDouble() < config.latencyProbability()) {
                long latencyMs = context.random().nextLong(config.minLatencyMs(), config.maxLatencyMs() + 1);
                context.logger()
                        .logFault(
                                FAULT_INJECTION,
                                context.scheduler().now(),
                                context.random().iteration())
                        .withString("lifecycle", "fault-triggered")
                        .withString("fault", "network-latency")
                        .withNumber("fault-id", faultIdSeq++)
                        .log();
                return latencyMs;
            }

            return 0;
        }

        private void induceRandomPartition(Config.NetworkConfig config) {
            var nodes = context.simulator().nodeNames();
            if (nodes.size() < 2) {
                return;
            }

            var sideA = new HashSet<String>();
            var sideB = new HashSet<String>();
            nodes.forEach(node -> (context.random().nextBoolean() ? sideA : sideB).add(node));
            if (sideA.isEmpty() || sideB.isEmpty()) {
                return;
            }
            long duration =
                    context.random().nextLong(config.minPartitionDurationMs(), config.maxPartitionDurationMs() + 1);
            int faultId = faultIdSeq++;
            context.logger()
                    .logFault(
                            FAULT_INJECTION,
                            context.scheduler().now(),
                            context.random().iteration())
                    .withString("lifecycle", "fault-started")
                    .withString("fault", "network-partition-fault")
                    .withNumber("fault-id", faultId)
                    .withPOJO("side-a", sideA)
                    .withPOJO("side-b", sideB)
                    .withNumber("durationMs", duration)
                    .log();
            context.network().partition(sideA, sideB);
            context.simulator()
                    .scheduleAfterDelay(
                            () -> {
                                context.lock().lock();
                                try {
                                    context.network().heal(sideA, sideB);
                                    context.logger()
                                            .logFault(
                                                    FAULT_INJECTION,
                                                    context.scheduler().now(),
                                                    context.random().iteration())
                                            .withString("lifecycle", "fault-ended")
                                            .withString("fault", "network-partition-fault")
                                            .withNumber("fault-id", faultId)
                                            .log();
                                } finally {
                                    context.lock().unlock();
                                }
                            },
                            duration,
                            MILLISECONDS);
        }

        /**
         * Hook called before a filesystem operation.
         *
         * @throws IOException if a filesystem fault occurs
         */
        void onFileSystemOp() throws IOException {
            var config = context.faults().fileSystem();
            if (context.simulator().isReady()
                    && config.enabled()
                    && config.ioErrorProbability() > 0
                    && context.random().nextDouble() < config.ioErrorProbability()) {
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
