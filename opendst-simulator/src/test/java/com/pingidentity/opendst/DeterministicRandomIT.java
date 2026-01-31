/*
 * Copyright 2025-2026 Ping Identity Corporation
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

import static com.diffplug.selfie.Selfie.expectSelfie;
import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.util.Map.of;
import static tools.jackson.jr.ob.JSON.Feature.PRETTY_PRINT_OUTPUT;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.jr.ob.JSON;

public class DeterministicRandomIT {
    @Test
    public void deterministicRandom() throws Exception {
        var ref = new AtomicReference<String>();
        var uuid = UUID.randomUUID().toString();
        runSimulation(() -> {
            var uuid2 = UUID.randomUUID().toString();
            ref.set(JSON.std
                    .with(PRETTY_PRINT_OUTPUT)
                    .asString(of(
                            "newRandom", randomToMap(new Random()),
                            "threadLocalRandom", randomToMap(ThreadLocalRandom.current()),
                            "splittableRandom", randomToMap(new SplittableRandom()),
                            "secureRandom", randomToMap(new SecureRandom()))));
            return null;
        });
        expectSelfie(ref.get()).toMatchDisk();
    }

    private Map<String, Object> randomToMap(RandomGenerator random) {
        var bytes = new byte[8];
        random.nextBytes(bytes);
        return of(
                "nextDouble",
                random.nextDouble(),
                "nextLong",
                random.nextLong(),
                "nextBoolean",
                random.nextBoolean(),
                "nextInt",
                random.nextInt(),
                "nextFloat",
                random.nextFloat(),
                "nextGaussian",
                random.nextGaussian(),
                "nextExponential",
                random.nextExponential(),
                "nextBytes",
                HexFormat.of().formatHex(bytes));
    }
}
