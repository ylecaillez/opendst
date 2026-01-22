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
import static java.lang.System.currentTimeMillis;
import static java.lang.System.nanoTime;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;
import static java.lang.Thread.startVirtualThread;
import static java.time.Duration.ofHours;
import static java.util.concurrent.ThreadLocalRandom.current;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.pingidentity.opendst.Simulator.SimulationError;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

public class SimulatorAgentIT {

    @Test
    public void simulatorReportsException() {
        var ise = new IllegalStateException("Simulated failure");
        assertThatExceptionOfType(SimulationError.class)
                .isThrownBy(() -> runSimulation(() -> {
                    throw ise;
                }));
    }

    @Test
    public void realSystemCurrentTimeMillis() throws Exception {
        long now = currentTimeMillis();
        // 1756022755 was the time at which this test was written: 2025-08-24 8:05:55 GMT
        assertThat(now).isGreaterThan(1756022755);
        sleep(500);
        assertThat(currentTimeMillis()).isGreaterThan(now);
    }

    @RepeatedTest(100)
    public void simulatedSystemCurrentTimeMillis() throws Exception {
        long realNow = currentTimeMillis();
        runSimulation(() -> {
            long now = currentTimeMillis();
            // 1445385600 is 2015-10-21
            assertThat(now).isGreaterThanOrEqualTo(SECONDS.toMillis(1445385600)).isLessThan(realNow);
            sleep(500);
            assertThat(currentTimeMillis()).isGreaterThan(now);
            return null;
        });
    }

    @Test
    public void realNanoTime() throws Exception {
        long now = nanoTime();
        sleep(500);
        assertThat(nanoTime()).isGreaterThan(now);
    }

    @RepeatedTest(100)
    public void simulatedNanoTime() throws Exception {
        runSimulation(() -> {
            long now = nanoTime();
            sleep(500);
            assertThat(nanoTime()).isGreaterThan(now);
            return null;
        });
    }

    @Test
    public void realNewThread() throws Exception {
        var task = new FutureTask<>(() -> currentThread().isVirtual());
        new Thread(task).start();
        assertThat(task.get(30, SECONDS)).isFalse();
    }

    @RepeatedTest(100)
    public void simulatedNewThread() throws Exception {
        var task = new FutureTask<>(() -> currentThread().isVirtual());
        runSimulation(() -> {
            new Thread(task).start();
            return null;
        });
        assertThat(task.get(30, SECONDS)).isTrue();
    }

    @Test
    public void realVirtualThread() {
        var task = new FutureTask<>(() -> {
            sleep(ofHours(42));
            return null;
        });
        startVirtualThread(task);
        assertThatException().isThrownBy(() -> task.get(10, SECONDS)).isInstanceOf(TimeoutException.class);
    }

    @RepeatedTest(100)
    public void simulatedVirtualThread() throws Exception {
        var task = new FutureTask<>(() -> {
            sleep(ofHours(42));
            return 1234;
        });
        runSimulation(() -> {
            startVirtualThread(task);
            return null;
        });
        assertThat(task).succeedsWithin(10, SECONDS).isEqualTo(1234);
    }

    @Test
    public void realThreadLocalRandom() throws Exception {
        byte[] run1 = new byte[1000];
        threadLocalRandomNextBytes(run1);

        byte[] run2 = new byte[1000];
        threadLocalRandomNextBytes(run2);

        assertThat(run1).isNotEqualTo(run2);
    }

    @RepeatedTest(100)
    public void simulatedThreadLocalRandom() throws Exception {
        byte[] run1 = new byte[1000];
        runSimulation(() -> threadLocalRandomNextBytes(run1));

        byte[] run2 = new byte[1000];
        runSimulation(() -> threadLocalRandomNextBytes(run2));

        assertThat(run1).isEqualTo(run2);
    }

    private Void threadLocalRandomNextBytes(byte[] random) throws Exception {
        var thread = new Thread(() -> current().nextBytes(random));
        thread.start();
        thread.join();
        return null;
    }
}
