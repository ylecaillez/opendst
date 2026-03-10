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
package com.pingidentity.opendst;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;

import java.time.Duration;

public record Config(
        Duration networkLatencyMin,
        Duration networkLatencyFast,
        Duration networkLatencySlow,
        Duration maxCloggingLatency,
        Duration maxBuggifiedDelay,
        double cloggingProbability,
        double disconnectProbability,
        double bounceProbability,
        Duration cloggingDurationMin,
        Duration cloggingDurationMax,
        Duration disconnectDuration,
        Duration bounceDuration,
        double partitionProbability,
        Duration partitionDuration,
        double stragglerProbability,
        Duration stragglerDuration,
        double slowThreadProbability,
        Duration slowThreadDuration,
        Duration slowThreadDelayMin,
        Duration slowThreadDelayMax,
        Duration faultInjectionDelay,
        Duration testDuration) {
    public static Config newConfig() {
        return new Config(
                ofNanos(100_000),
                ofNanos(800_000),
                ofMillis(100),
                ofMillis(100),
                ofMillis(200),
                0.5, // cloggingProbability
                0.1, // disconnectProbability
                0.05, // bounceProbability
                ofMillis(100), // cloggingDurationMin
                ofMillis(1000), // cloggingDurationMax
                ofMillis(1000), // disconnectDuration
                ofMillis(5000), // bounceDuration
                0.02, // partitionProbability
                ofMillis(5000), // partitionDuration
                0.05, // stragglerProbability
                ofMillis(5000), // stragglerDuration
                0.01, // slowThreadProbability
                ofMillis(5000), // slowThreadDuration
                ofMillis(100), // slowThreadDelayMin
                ofMillis(1000), // slowThreadDelayMin
                ofMillis(1000), // faultInjectionDelay
                Duration.ofMinutes(1) // testDuration
        );
    }
}
