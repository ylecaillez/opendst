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
package com.pingidentity.opendst.testapp;

import static java.lang.Thread.sleep;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class DeterminismStressor {

    public void stress(int data) {
        Thread coordinator = new Thread(() -> runStress(data), "Stressor-Coordinator");
        coordinator.start();
        try {
            coordinator.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (coordinator.isAlive()) {
            coordinator.interrupt();
        }
    }

    private void runStress(int data) {
        var random = new Random();
        var lock = new ReentrantLock();
        var monitor = new Object();
        var queue = new ArrayBlockingQueue<Integer>(Math.max(1, data % 5 + 1));
        var latch = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try {
                consumeEntropy();
                int count = 5 + random.nextInt(10);
                for (int i = 0; i < count; i++) {
                    if (random.nextBoolean()) {
                        lock.lock();
                        try {
                            sleep(random.nextInt(5));
                        } finally {
                            lock.unlock();
                        }
                    }
                    if (random.nextBoolean()) {
                        queue.offer(i, 10, TimeUnit.MILLISECONDS);
                    } else {
                        queue.put(i);
                    }
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }
            } catch (Exception e) {
                // Ignore
            } finally {
                latch.countDown();
            }
        }, "Stressor-Prod");

        Thread t2 = new Thread(() -> {
            try {
                consumeEntropy();
                int count = 5 + random.nextInt(10);
                for (int i = 0; i < count; i++) {
                    if (random.nextBoolean()) {
                        queue.poll(10, TimeUnit.MILLISECONDS);
                    } else {
                        queue.take();
                    }
                    synchronized (monitor) {
                        if (random.nextBoolean()) {
                            monitor.wait(random.nextInt(10));
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            } finally {
                latch.countDown();
            }
        }, "Stressor-Cons");

        t1.start();
        t2.start();

        try {
            latch.await(400, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void consumeEntropy() throws InterruptedException {
        /* try (var is = new FileInputStream("/dev/urandom")) {
            int data = is.read();
            Thread.sleep(data * 10);
        } catch (IOException e) {
        } */

        int result = 0;

        // 1. SecureRandom relies on OS entropy/devices which is non-deterministic
        SecureRandom secureRandom = new SecureRandom();
        result += secureRandom.nextInt();

        // 2. Object identity hash codes are dependent on memory address/internal state
        // which varies between runs.
        Object marker = new Object();
        result ^= System.identityHashCode(marker);

        // 3. HashSet iteration order depends on hash codes, effectively unpredictable
        //  across JVM runs for default objects.
        Set<Object> set = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            set.add(new Object());
        }

        int shift = 0;
        for (Object o : set) {
            result += System.identityHashCode(o) << (shift++ % 3);
        }

        // 4. HashMap key iteration
        Map<Object, Integer> map = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            map.put(new Object(), i);
        }
        for (Map.Entry<Object, Integer> entry : map.entrySet()) {
            result ^= System.identityHashCode(entry.getKey()) + entry.getValue();
        }

        sleep(Math.abs(result % 10_000));
    }
}
