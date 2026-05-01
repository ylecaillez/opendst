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

import java.lang.reflect.Method;

/**
 * Issues the nyx-lite snapshot hypercall at segment boundaries.
 *
 * <p>The {@code opendst.nyx.guest.Hypercall} class is only available in the nyx-lite
 * guest environment. This class uses reflection so the agent JAR has no compile-time
 * dependency on the nyx guest JAR.
 */
final class NyxSegmentHypercall {

    private static volatile Method snapshotMethod;
    private static volatile Method bootSnapshotMethod;
    private static volatile boolean resolved;

    private static void resolve() {
        if (!resolved) {
            resolved = true;
            try {
                // Hypercall is in nyx-guest.jar on the system classpath; the agent runs
                // under the bootstrap classloader so we must ask the system classloader.
                var cls = Class.forName("opendst.nyx.guest.Hypercall", true, ClassLoader.getSystemClassLoader());
                snapshotMethod = cls.getDeclaredMethod("snapshot");
                snapshotMethod.setAccessible(true);
                bootSnapshotMethod = cls.getDeclaredMethod("bootSnapshot");
                bootSnapshotMethod.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                // Not running in nyx-lite VM — no-op
            }
        }
    }

    static void requestSnapshot() {
        resolve();
        if (snapshotMethod != null) {
            try {
                snapshotMethod.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("nyx snapshot hypercall failed", e);
            }
        }
    }

    static void requestBootSnapshot() {
        resolve();
        if (bootSnapshotMethod != null) {
            try {
                bootSnapshotMethod.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("nyx boot snapshot hypercall failed", e);
            }
        }
    }

    private NyxSegmentHypercall() {}
}
