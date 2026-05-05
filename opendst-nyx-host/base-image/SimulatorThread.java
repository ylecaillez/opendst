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
package java.lang;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A virtual thread base class for {@code Thread} usage under deterministic simulation.
 *
 * <p><strong>Experimental:</strong> This feature may be reverted if it causes more issues than
 * it solves. The approach of patching {@code java.base} and rewriting superclass hierarchies is
 * inherently fragile and may break with JDK updates or unusual Thread subclass patterns.
 *
 * <p>At build time, the OpenDST Maven plugin rewrites both:
 * <ul>
 *   <li>{@code new Thread(runnable)} → {@code new SimulatorThread(runnable)}</li>
 *   <li>{@code class Foo extends Thread} → {@code class Foo extends SimulatorThread}</li>
 * </ul>
 * This class extends {@code VirtualThread} (made non-final via bytecode patching), so all
 * thread creation runs under the deterministic scheduler.
 *
 * <p>Two static fields provide the integration points for the simulation agent:
 * <ul>
 *   <li>{@code EXECUTOR_SUPPLIER} — supplies the per-node deterministic scheduler</li>
 *   <li>{@code ON_CONSTRUCT} — callback invoked after construction for node attachment</li>
 * </ul>
 *
 * <p>Each constructor uses a <em>trampoline</em> pattern: it passes a Runnable that calls
 * {@code Thread.currentThread().run()} to VirtualThread's constructor. When the virtual thread
 * starts, {@code currentThread()} returns the actual instance, so {@code .run()} dispatches
 * to either the stored {@code target} Runnable (for {@code new Thread(runnable)}) or the
 * subclass's overridden {@code run()} method.
 */
public class SimulatorThread extends VirtualThread {

    /** Supplies the per-node deterministic executor. Set by the simulation agent at startup. */
    public static volatile Supplier<Executor> EXECUTOR_SUPPLIER;

    /** Callback invoked after each constructor to attach the thread to the simulation node. */
    public static volatile Consumer<Thread> ON_CONSTRUCT;

    /**
     * The target Runnable for direct {@code new Thread(runnable)} usage.
     * {@code null} for subclasses that override {@code run()}.
     */
    private Runnable target;

    /**
     * Dispatches to the stored {@code target} Runnable if present (direct Thread usage),
     * otherwise does nothing — subclasses override this method.
     */
    @Override
    public void run() {
        if (target != null) {
            target.run();
        }
    }

    // ---- Helper methods ----

    /**
     * Trampoline used as the {@code Runnable} target passed to {@code VirtualThread}'s constructor.
     * When the virtual thread starts, {@code currentThread()} returns the subclass instance,
     * so {@code .run()} dispatches to the overridden method.
     */
    private static void trampoline() {
        Thread.currentThread().run();
    }

    /** Returns the per-node executor. */
    private static Executor getSimulatorExecutor() {
        return EXECUTOR_SUPPLIER.get();
    }

    /** Attaches the given thread to the current simulation node. */
    private static void attachThread(Thread t) {
        ON_CONSTRUCT.accept(t);
    }

    // ---- Public Thread constructors ----

    /** Mirrors {@link Thread#Thread()}. */
    public SimulatorThread() {
        super(getSimulatorExecutor(), "", 0, SimulatorThread::trampoline);
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(Runnable)}. */
    public SimulatorThread(Runnable target) {
        super(getSimulatorExecutor(), "", 0, SimulatorThread::trampoline);
        this.target = target;
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(String)}. */
    public SimulatorThread(String name) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(ThreadGroup, String)}. {@code group} is silently ignored (virtual threads have no ThreadGroup). */
    public SimulatorThread(ThreadGroup group, String name) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(ThreadGroup, Runnable)}. {@code group} is silently ignored (virtual threads have no ThreadGroup). */
    public SimulatorThread(ThreadGroup group, Runnable target) {
        super(getSimulatorExecutor(), "", 0, SimulatorThread::trampoline);
        this.target = target;
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(Runnable, String)}. */
    public SimulatorThread(Runnable target, String name) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        this.target = target;
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(ThreadGroup, Runnable, String)}. {@code group} is silently ignored. */
    public SimulatorThread(ThreadGroup group, Runnable target, String name) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        this.target = target;
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(ThreadGroup, Runnable, String, long)}. {@code group} and {@code stackSize} are silently ignored. */
    public SimulatorThread(ThreadGroup group, Runnable target, String name, long stackSize) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        this.target = target;
        attachThread(this);
    }

    /** Mirrors {@link Thread#Thread(ThreadGroup, Runnable, String, long, boolean)}. {@code group}, {@code stackSize}, and {@code inheritThreadLocals} are silently ignored. */
    public SimulatorThread(ThreadGroup group, Runnable target, String name, long stackSize,
                           boolean inheritThreadLocals) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        this.target = target;
        attachThread(this);
    }

    // ---- Package-private Thread constructors ----

    /** Mirrors package-private {@code Thread(ThreadGroup, String, int, Runnable, long)}. {@code group}, {@code characteristics}, and {@code stackSize} are silently ignored. */
    SimulatorThread(ThreadGroup group, String name, int characteristics, Runnable target, long stackSize) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        this.target = target;
        attachThread(this);
    }

    /** Mirrors package-private {@code Thread(String, int, boolean)}. {@code characteristics} and {@code bound} are silently ignored. */
    SimulatorThread(String name, int characteristics, boolean bound) {
        super(getSimulatorExecutor(), name, 0, SimulatorThread::trampoline);
        attachThread(this);
    }
}
