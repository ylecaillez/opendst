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
import static java.util.Objects.requireNonNull;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A utility class that stresses the simulator's ability to maintain determinism.
 * It uses several JDK APIs that are typically sources of non-determinism:
 * <ul>
 *   <li>{@link Random}: Seeded by the simulator.</li>
 *   <li>{@link System#currentTimeMillis()}: Redirected to virtual time.</li>
 *   <li>Collection iteration ({@link HashSet}, {@link HashMap}): Iteration
 *       order must be stable.</li>
 *   <li>Garbage Collection and {@link ReferenceQueue}: Ensures GC activity
 *       doesn't leak non-determinism.</li>
 * </ul>
 */
public final class DeterminismStressor {

    private final Random random = new Random();
    private final Map<Integer, String> map = new HashMap<>();
    private final Set<String> set = new HashSet<>();
    private final ReferenceQueue<Object> refQueue = new ReferenceQueue<>();
    private final List<WeakReference<Object>> weakRefs = new ArrayList<>();

    public void stress(int seed) {
        random.setSeed(seed);

        // 1. Stress collections and iteration order
        for (int i = 0; i < 10; i++) {
            int val = random.nextInt(1000);
            map.put(val, "val-" + val);
            set.add("set-" + val);
        }

        // Iteration MUST be deterministic
        for (var entry : map.entrySet()) {
            noop(entry.getKey() + entry.getValue());
        }
        for (var s : set) {
            noop(s);
        }

        // 2. Stress time and scheduling
        try {
            long start = System.currentTimeMillis();
            sleep(random.nextInt(10, 50));
            long end = System.currentTimeMillis();
            assert end >= start;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Stress I/O
        try (var _ = new FileInputStream("pom.xml")) {
            noop("io-op");
        } catch (IOException ignored) {
        }

        // 4. Stress ReferenceQueue and GC (indirectly)
        Object obj = new Object();
        weakRefs.add(new WeakReference<>(obj, refQueue));
        if (random.nextBoolean()) {
            System.gc();
        }
        while (refQueue.poll() != null) {
            noop("gc-event");
        }
    }

    private void noop(Object obj) {
        requireNonNull(obj);
    }
}
