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
package com.pingidentity.opendst.it.fray;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pingidentity.opendst.sdk.Assert;
import com.pingidentity.opendst.sdk.Signals;

/**
 * Integration test suite of schedule-dependent concurrency bugs derived from the Fray
 * project's {@code core/fail} scenarios (https://github.com/cmu-pasta/fray, Apache 2.0,
 * Copyright CMU PASTA Lab). Each scenario exercises a bug that only manifests under
 * specific thread interleavings, validated with {@link Assert#sometimes}.
 *
 * <p>The deadlock scenario runs inside a worker thread; after {@value #DEADLOCK_TIMEOUT_MS} ms
 * of virtual time the observer checks whether the worker is still alive. The safety-bug
 * scenarios always terminate but capture incorrect behaviour in an {@link AtomicBoolean}.
 */
public final class FrayBugSuite {

    /** Virtual-time timeout used to observe whether a scenario has deadlocked (ms). */
    private static final long DEADLOCK_TIMEOUT_MS = 1_000;

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Signals.ready();

        // Schedule-dependent deadlock — Assert labels must be string literals for offline discovery.
        // deadlocked() runs the body in a worker and returns true if it is still alive
        // after DEADLOCK_TIMEOUT_MS of virtual time.
        Assert.sometimes(
                deadlocked(FrayBugSuite::synchronizedMethodDeadlock),
                "synchronized-method-deadlock",
                null);

        // Schedule-dependent safety bugs — all threads always terminate, but incorrect
        // behaviour is observable under specific interleavings.
        notifyOrder();

        pending_rescheduleBeforeWaitReacquireMonitorLock();
        pending_waitSpuriousWakeup();

        // Exception bug — always reproducible, validated with Assert.always
        waitWithoutMonitorLock();
    }

    /**
     * Runs a potentially deadlocking scenario in a worker thread and returns whether the
     * worker is still alive after {@value #DEADLOCK_TIMEOUT_MS} ms of virtual time. A live
     * worker after the timeout proves the scenario deadlocked in this simulation run.
     */
    private static boolean deadlocked(ThrowingRunnable body) throws InterruptedException {
        Thread worker = new Thread(() -> {
            try {
                body.run();
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                throw new AssertionError("Unexpected exception in deadlock scenario", e);
            }
        });
        worker.start();
        worker.join(DEADLOCK_TIMEOUT_MS);
        return worker.isAlive();
    }

    // ---- Schedule-dependent deadlock ---------------------------------------------

    /**
     * Circular deadlock via synchronized methods: {@code c1.m()} acquires c1's lock,
     * sleeps, then calls {@code c2.last()} (needs c2's lock); {@code c2.m()} does the
     * inverse. The sleep widens the interleaving window so that both threads can hold
     * their first lock simultaneously before competing for the second. The deadlock is
     * schedule-dependent: if one thread completes before the other acquires its first
     * lock, no deadlock occurs.
     *
     * <p>Source: fray SynchronizedMethodDeadlock.java
     */
    private static void synchronizedMethodDeadlock() throws InterruptedException {
        C1 c1 = new C1();
        C2 c2 = new C2();
        Thread t1 = new Thread(() -> c1.m(c2));
        Thread t2 = new Thread(() -> c2.m(c1));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
    }

    // ---- Schedule-dependent safety bugs (asserted) --------------------------------

    /**
     * Notify-order bug: t1 and t2 both wait on a monitor guarded by a flag. The main
     * thread sets the flag and calls {@code notifyAll()}. After waking, t1 sets a
     * secondary flag; t2 then checks it. If t2 is scheduled before t1 after the
     * {@code notifyAll()}, t2 observes the secondary flag unset — the bug.
     *
     * <p>Source: fray NotifyOrder.java
     */
    private static void notifyOrder() throws InterruptedException {
        Object o = new Object();
        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean notifyFlag = new AtomicBoolean(false);
        AtomicBoolean flag = new AtomicBoolean(false);
        AtomicBoolean bugFound = new AtomicBoolean(false);
        Thread t1 = new Thread(() -> {
            synchronized (o) {
                try {
                    latch.countDown();
                    while (!notifyFlag.get()) {
                        o.wait();
                    }
                    flag.set(true);
                } catch (InterruptedException ignored) {
                }
            }
        });
        Thread t2 = new Thread(() -> {
            synchronized (o) {
                try {
                    latch.countDown();
                    while (!notifyFlag.get()) {
                        o.wait();
                    }
                    if (!flag.get()) {
                        bugFound.set(true);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        });
        t1.start();
        t2.start();
        latch.await();
        synchronized (o) {
            notifyFlag.set(true);
            o.notifyAll();
        }
        t1.join();
        t2.join();
        Assert.sometimes(bugFound.get(), "notify-order", null);
    }

    // ---- pending_* scenarios: run but not asserted (simulator support missing) ----

    /**
     * Reschedule-before-wait-reacquire bug: after {@code notify()} wakes a thread, the
     * notifying thread can re-enter the monitor before the woken thread re-acquires it.
     * The worker sets a flag after re-acquiring the monitor; if the main thread checks
     * the flag first, it observes it unset — the bug.
     *
     * <p>Source: fray RescheduleBeforeWaitReacquireMonitorLock.java
     */
    private static void pending_rescheduleBeforeWaitReacquireMonitorLock() throws InterruptedException {
        Object o = new Object();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean flag = new AtomicBoolean(false);
        AtomicBoolean bugFound = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            synchronized (o) {
                try {
                    latch.countDown();
                    o.wait();
                    flag.set(true);
                } catch (InterruptedException ignored) {
                }
            }
        });
        t.start();
        latch.await();
        synchronized (o) {
            o.notify();
        }
        synchronized (o) {
            if (!flag.get()) {
                bugFound.set(true);
            }
        }
        t.join();
        // Not yet asserted: bugFound is never set without Thread.yield() because OpenDST
        // does not expose a scheduling point between notify() and re-acquisition of the
        // monitor by the woken thread.
    }

    /**
     * Spurious-wakeup bug: the worker guards its {@code wait()} with an {@code if} instead
     * of a {@code while}. A spurious wakeup returns from {@code wait()} without a matching
     * {@code notify()}, causing the worker to proceed before the {@code ready} flag is set.
     *
     * <p>Without spurious-wakeup injection the worker always wakes via the real
     * {@code notify()}, so {@code ready} is always true and the bug is never observable.
     *
     * <p>Source: fray WaitSpuriousWakeup.java
     */
    private static void pending_waitSpuriousWakeup() throws InterruptedException {
        Object o = new Object();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicBoolean bugFound = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            synchronized (o) {
                latch.countDown();
                try {
                    if (!ready.get()) { // BUG: should be while
                        o.wait();
                    }
                    if (!ready.get()) {
                        bugFound.set(true);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        });
        t.start();
        latch.await();
        synchronized (o) {
            ready.set(true);
            o.notify();
        }
        t.join();
        // Not yet asserted: OpenDST does not yet inject spurious wakeups into Object.wait(),
        // so bugFound is never set — the woken thread always sees ready == true.
    }

    // ---- Exception bug ------------------------------------------------------------

    /**
     * Calling {@code Object.wait()} without holding the monitor always throws
     * {@link IllegalMonitorStateException}. Validated with {@link Assert#always} since
     * the exception is deterministic (schedule-independent).
     *
     * <p>Source: fray WaitWithoutMonitorLock.java
     */
    private static void waitWithoutMonitorLock() throws InterruptedException {
        AtomicBoolean threw = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            Object o = new Object();
            try {
                o.wait();
            } catch (IllegalMonitorStateException e) {
                threw.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        worker.start();
        worker.join();
        Assert.always(threw.get(), "wait-without-monitor-lock/always-throws", null);
    }

    // ---- Helper classes for synchronizedMethodDeadlock ----------------------------

    static final class C1 {
        synchronized void m(C2 c2) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            c2.last();
        }

        synchronized void last() {}
    }

    static final class C2 {
        synchronized void m(C1 c1) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            c1.last();
        }

        synchronized void last() {}
    }
}
