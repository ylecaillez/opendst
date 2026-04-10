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
package com.pingidentity.opendst.it.thread;

import com.pingidentity.opendst.sdk.Assert;
import java.util.logging.LogManager;

/**
 * Exercises thread-related determinism guards under OpenDST simulation.
 *
 * <p>Tests two areas:
 * <ol>
 *   <li><strong>Platform thread shutdown hooks</strong> — {@link LogManager} initialization
 *       registers a {@code LogManager$Cleaner} shutdown hook (a platform thread). The agent
 *       must skip it to preserve determinism.</li>
 *   <li><strong>Thread subclasses</strong> — Direct and transitive {@code Thread} subclasses
 *       are rewritten to extend {@code SimulatorThread} and must run correctly under simulation
 *       (identity, instanceof, join, overridden run()).</li>
 * </ol>
 */
public final class ThreadApp {

    /** Direct Thread subclass with a named constructor. */
    static class WorkerThread extends Thread {
        private volatile boolean completed;

        WorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            Assert.reachable("worker-run");

            // Verify currentThread() returns this instance
            var current = Thread.currentThread();
            if (current != this) {
                throw new AssertionError(
                        "currentThread() returned wrong instance: expected " + this + " but got " + current);
            }
            Assert.reachable("worker-identity");

            // Verify instanceof
            if (!(current instanceof WorkerThread)) {
                throw new AssertionError("currentThread() is not instanceof WorkerThread: " + current.getClass());
            }
            Assert.reachable("worker-instanceof");

            completed = true;
        }
    }

    /** Transitive subclass: extends WorkerThread (not Thread directly). */
    static class SpecialWorkerThread extends WorkerThread {
        private volatile boolean specialCompleted;

        SpecialWorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            super.run();
            Assert.reachable("special-worker-run");

            if (!(Thread.currentThread() instanceof SpecialWorkerThread)) {
                throw new AssertionError(
                        "currentThread() is not instanceof SpecialWorkerThread: "
                                + Thread.currentThread().getClass());
            }
            Assert.reachable("special-worker-instanceof");
            specialCompleted = true;
        }
    }

    /** Thread subclass using the no-arg constructor. */
    static class SimpleThread extends Thread {
        private volatile boolean completed;

        @Override
        public void run() {
            Assert.reachable("simple-run");
            completed = true;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // ---- Shutdown hook guard ----
        // Force LogManager initialization inside the simulation context.
        // This triggers registration of LogManager$Cleaner (a platform thread shutdown hook)
        // through the intercepted Runtime.addShutdownHook() path.
        LogManager.getLogManager();
        Assert.reachable("shutdown-hook-completed");

        // ---- Thread subclasses ----

        // Direct Thread subclass with named constructor
        var worker = new WorkerThread("test-worker");
        worker.start();
        worker.join(5000);
        if (!worker.completed) {
            throw new AssertionError("WorkerThread did not complete");
        }
        Assert.reachable("worker-joined");

        // Transitive subclass
        var special = new SpecialWorkerThread("special-worker");
        special.start();
        special.join(5000);
        if (!special.specialCompleted) {
            throw new AssertionError("SpecialWorkerThread did not complete");
        }
        Assert.reachable("special-joined");

        // No-arg constructor subclass
        var simple = new SimpleThread();
        simple.start();
        simple.join(5000);
        if (!simple.completed) {
            throw new AssertionError("SimpleThread did not complete");
        }
        Assert.reachable("simple-joined");

        Assert.reachable("all-done");
    }
}
