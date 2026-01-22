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

import static com.pingidentity.opendst.Simulator.runSimulation;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

public class DeterministicRandomIT {

    @RepeatedTest(10)
    public void deterministicRandom() throws Exception {
        runSimulation(() -> {
            var rnd = new Random();
            assertThat(rnd.nextDouble()).isEqualTo(0.27707849007413665d);
            assertThat(rnd.nextLong()).isEqualTo(-6169532649852302182L);
            assertThat(rnd.nextBoolean()).isEqualTo(true);
            assertThat(rnd.nextInt()).isEqualTo(1938135004);
            assertThat(rnd.nextFloat()).isEqualTo(0.36878288f);
            assertThat(rnd.nextGaussian()).isEqualTo(-0.9454991660246921);
            assertThat(rnd.nextExponential()).isEqualTo(3.385775170914356);
            var bytes = new byte[8];
            rnd.nextBytes(bytes);
            assertThat(bytes).isEqualTo(new byte[] {-29, -95, -120, -1, -15, 16, 89, -21});
            return null;
        });
    }

    @RepeatedTest(10)
    public void deterministicThreadLocalRandom() throws Exception {
        runSimulation(() -> {
            var rnd = ThreadLocalRandom.current();
            assertThat(rnd.nextDouble()).isEqualTo(0.5974114225552637);
            assertThat(rnd.nextLong()).isEqualTo(-2349696411640184114L);
            assertThat(rnd.nextBoolean()).isEqualTo(false);
            assertThat(rnd.nextInt()).isEqualTo(2075148625);
            assertThat(rnd.nextFloat()).isEqualTo(0.68975097f);
            assertThat(rnd.nextGaussian()).isEqualTo(-0.40086993610549193);
            assertThat(rnd.nextExponential()).isEqualTo(1.0510764817041371);
            var bytes = new byte[8];
            rnd.nextBytes(bytes);
            assertThat(bytes).isEqualTo(new byte[] {-66, 110, -46, -106, -2, 90, 67, -104});
            return null;
        });
    }

    @RepeatedTest(10)
    public void deterministicSplittableRandom() throws Exception {
        runSimulation(() -> {
            var rnd = new SplittableRandom();
            assertThat(rnd.nextDouble()).isEqualTo(0.07065650453484762);
            assertThat(rnd.nextLong()).isEqualTo(219458345119832653L);
            assertThat(rnd.nextBoolean()).isEqualTo(true);
            assertThat(rnd.nextInt()).isEqualTo(-1673160038);
            assertThat(rnd.nextFloat()).isEqualTo(0.791648f);
            assertThat(rnd.nextGaussian()).isEqualTo(2.408389380157492);
            assertThat(rnd.nextExponential()).isEqualTo(1.4971329162213336);
            var bytes = new byte[8];
            rnd.nextBytes(bytes);
            assertThat(bytes).isEqualTo(new byte[] {111, -68, -26, 38, -15, 16, 89, -21});
            return null;
        });
    }

    @Test
    public void deterministicSecureRandom() throws Exception {
        runSimulation(() -> {
            var rnd = new SecureRandom();
            assertThat(rnd.nextDouble()).isEqualTo(0.5141285870643456);
            assertThat(rnd.nextLong()).isEqualTo(-4725857477910044393L);
            assertThat(rnd.nextBoolean()).isEqualTo(true);
            assertThat(rnd.nextInt()).isEqualTo(-594049677);
            assertThat(rnd.nextFloat()).isEqualTo(0.5998292f);
            assertThat(rnd.nextGaussian()).isEqualTo(-0.9454991660246921);
            assertThat(rnd.nextExponential()).isEqualTo(0.2807672725891044);
            var bytes = new byte[8];
            rnd.nextBytes(bytes);
            assertThat(bytes).isEqualTo(new byte[] {-29, -95, -120, -1, -15, 16, 89, -21});
            return null;
        });
    }
}
