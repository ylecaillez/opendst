/*
 * Copyright 2024-2026 Ping Identity Corporation
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

import static com.pingidentity.opendst.Node.currentNodeOrNull;
import static com.pingidentity.opendst.Node.nodeForThreadOrNull;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.clearNextVirtualThread;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.compareAndSetOnWaitingList;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.getNextVirtualThread;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.isOnWaitingList;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.takeVirtualThreadListToUnblock;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.unblockVirtualThread;
import static java.lang.String.format;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.onSpinWait;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;
import net.bytebuddy.asm.Advice.Return;
import net.bytebuddy.implementation.StubMethod;

/**
 * Functional module for thread simulation and instrumentation.
 */
public final class ThreadsInterceptors {

    /** Exposes internal methods of {@link Thread}. */
    public static final class Internals {
        private static final Class<?> VTHREAD_CLASS;
        private static final VarHandle VTHREAD_NEXT;
        private static final VarHandle VTHREAD_ON_WAITING_LIST;
        private static final MethodHandle VTHREAD_CAS_ON_WAITING_LIST;
        private static final MethodHandle VTHREAD_TAKE_LIST_TO_UNBLOCK;
        private static final MethodHandle VTHREAD_UNBLOCK;
        private static final MethodHandle THREAD_LOCAL_GET;
        private static final MethodHandle THREAD_LOCAL_SET;

        static {
            try {
                VTHREAD_CLASS = privateLookupIn(Thread.class, lookup()).findClass("java.lang.VirtualThread");
                VTHREAD_NEXT =
                        privateLookupIn(VTHREAD_CLASS, lookup()).findVarHandle(VTHREAD_CLASS, "next", VTHREAD_CLASS);
                VTHREAD_ON_WAITING_LIST = privateLookupIn(VTHREAD_CLASS, lookup())
                        .findVarHandle(VTHREAD_CLASS, "onWaitingList", boolean.class);
                VTHREAD_CAS_ON_WAITING_LIST = privateLookupIn(VTHREAD_CLASS, lookup())
                        .findVirtual(
                                VTHREAD_CLASS,
                                "compareAndSetOnWaitingList",
                                methodType(boolean.class, boolean.class, boolean.class));
                VTHREAD_TAKE_LIST_TO_UNBLOCK = privateLookupIn(VTHREAD_CLASS, lookup())
                        .findStatic(VTHREAD_CLASS, "takeVirtualThreadListToUnblock", methodType(VTHREAD_CLASS));
                VTHREAD_UNBLOCK = privateLookupIn(VTHREAD_CLASS, lookup())
                        .findVirtual(VTHREAD_CLASS, "unblock", methodType(void.class));
                THREAD_LOCAL_GET = privateLookupIn(ThreadLocal.class, lookup())
                        .findVirtual(ThreadLocal.class, "get", methodType(Object.class, Thread.class));
                THREAD_LOCAL_SET = privateLookupIn(ThreadLocal.class, lookup())
                        .findVirtual(ThreadLocal.class, "set", methodType(void.class, Thread.class, Object.class));
            } catch (Throwable e) {
                throw new Simulator.SimulationError("Unable to find virtual thread internal APIs", e);
            }
        }

        static Thread takeVirtualThreadListToUnblock() {
            try {
                return (Thread) VTHREAD_CLASS.cast(VTHREAD_TAKE_LIST_TO_UNBLOCK.invoke());
            } catch (Throwable e) {
                throw new Simulator.SimulationError("Unable to get the next virtual thread to unblock", e);
            }
        }

        static Thread getNextVirtualThread(Thread thread) {
            return (Thread) VTHREAD_NEXT.get(thread);
        }

        static void clearNextVirtualThread(Thread thread) {
            VTHREAD_NEXT.set(thread, null);
        }

        static boolean isOnWaitingList(Thread thread) {
            return (boolean) VTHREAD_ON_WAITING_LIST.get(thread);
        }

        static boolean compareAndSetOnWaitingList(Thread thread, boolean expected, boolean update) {
            try {
                return (boolean) VTHREAD_CAS_ON_WAITING_LIST.invoke(thread, expected, update);
            } catch (Throwable e) {
                throw new Simulator.SimulationError(e);
            }
        }

        static Object getThreadLocal(Thread thread, ThreadLocal<?> threadLocal) {
            try {
                return THREAD_LOCAL_GET.invoke(threadLocal, thread);
            } catch (Throwable e) {
                throw new Simulator.SimulationError(e);
            }
        }

        static Object setThreadLocal(Thread thread, ThreadLocal<?> threadLocal, Object value) {
            try {
                return THREAD_LOCAL_SET.invoke(threadLocal, thread, value);
            } catch (Throwable e) {
                throw new Simulator.SimulationError(e);
            }
        }

        public static void unblockVirtualThread(Thread vthread) {
            try {
                VTHREAD_UNBLOCK.invoke(vthread);
            } catch (Throwable e) {
                throw new Simulator.SimulationError(e);
            }
        }

        private Internals() {}
    }

    /**
     * Replaces {@code VirtualThread#unblockVirtualThreads()} which processes virtual threads exiting object monitor.
     */
    public static final class VirtualThreadUnblocker {
        /** Unblocks all the virtual threads present on the waiting list. */
        public static void unblockVirtualThreads() {
            for (; ; ) {
                var vthread = takeVirtualThreadListToUnblock();
                while (vthread != null) {
                    var nextThread = getNextVirtualThread(vthread);
                    clearNextVirtualThread(vthread);
                    if (nodeForThreadOrNull(vthread) == null) {
                        // Only unblock virtual threads scheduled on standard JVM's executor. The virtual threads
                        // scheduled on the Simulator's scheduler will be unblocked after task execution by an
                        // invocation to unblockSimulatorBackedThread()
                        compareAndSetOnWaitingList(vthread, true, false);
                        unblockVirtualThread(vthread);
                    }
                    vthread = nextThread;
                }
            }
        }

        public static void unblockSimulatorBackedThread(Thread thread) {
            requireNonNull(thread);
            if (thread.isAlive() && thread.getState() != TERMINATED) {
                if (isOnWaitingList(thread)) {
                    while (getNextVirtualThread(thread) != null) {
                        // Wait for unblockVirtualThreads() to detach this thread from the waiting list
                        onSpinWait();
                    }
                    if (!compareAndSetOnWaitingList(thread, true, false)) {
                        throw new Simulator.SimulationError(format(
                                "Thread '%s' is no more present on the waiting-list. Determinism is broken",
                                thread.getName()));
                    }
                    ThreadsInterceptors.Internals.unblockVirtualThread(thread);
                }
            }
        }

        private VirtualThreadUnblocker() {}
    }

    /** Overrides {@code java.lang.ThreadBuilders#newVirtualThread(Executor, String, int, Runnable)}. */
    @Intercepts("java.lang.ThreadBuilders#newVirtualThread(Executor,String,int,Runnable)")
    public static final class NewVirtualThreadAdvice {
        @OnMethodEnter
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(value = 0, readOnly = false) Executor executor) {
            var node = currentNodeOrNull();
            if (node != null) {
                executor = node::scheduleNow;
            }
            return node;
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Node node, @Return Thread thread) {
            if (node != null) {
                node.attachThread(thread);
            }
        }
    }

    /**
     * Intercepts platform thread creation in {@code ThreadBuilders} to replace platform threads
     * with virtual threads when inside a simulation context.
     *
     * <p>Two JDK-internal paths create platform threads:
     * <ul>
     *   <li>{@code ThreadBuilders$PlatformThreadBuilder.unstarted(Runnable)} — behind
     *       {@code Thread.ofPlatform().unstarted(r)} and {@code .start(r)}</li>
     *   <li>{@code ThreadBuilders$PlatformThreadFactory.newThread(Runnable)} — behind
     *       {@code Thread.ofPlatform().factory()} and {@code Executors.defaultThreadFactory()}</li>
     * </ul>
     *
     * <p>Inside simulation: the original method still runs (creating a platform thread that is
     * immediately discarded), and the return value is replaced with a virtual thread created via
     * {@code Thread.ofVirtual().unstarted(runnable)} — which is intercepted by
     * {@link NewVirtualThreadAdvice} to wire the node's executor and attach the thread.
     * The thread name and uncaught exception handler are copied from the discarded
     * platform thread to preserve the caller's configuration
     * (e.g. counter-based names from {@code Thread.ofPlatform().name("pool-", 0)}).
     *
     * <p>Outside simulation: the advice is a no-op and the platform thread passes through.
     */
    @Intercepts("java.lang.ThreadBuilders$PlatformThreadFactory#newThread(Runnable)")
    @Intercepts("java.lang.ThreadBuilders$PlatformThreadBuilder#unstarted(Runnable)")
    public static final class NewPlatformThreadAdvice {
        @OnMethodEnter
        @SuppressWarnings("MissingJavadocMethod")
        public static Runnable onEnter(@Argument(0) Runnable target) {
            return currentNodeOrNull() != null ? target : null;
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Runnable target, @Return(readOnly = false) Thread thread) {
            if (target != null) {
                var name = thread.getName();
                var ueh = thread.getUncaughtExceptionHandler();
                thread = Thread.ofVirtual().name(name).unstarted(target);
                // Copy the UEH only if explicitly set (not the default ThreadGroup fallback)
                if (ueh != null && !(ueh instanceof ThreadGroup)) {
                    thread.setUncaughtExceptionHandler(ueh);
                }
            }
        }
    }

    /** Overrides {@link Thread#setDaemon(boolean)}. */
    @Intercepts("java.lang.Thread#setDaemon(boolean)")
    public static final class ThreadSetDaemonAdvice {
        @OnMethodEnter
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onEnter(@Argument(value = 0, readOnly = false) boolean on) {
            var node = currentNodeOrNull();
            if (node != null) {
                on = true;
            }
        }
    }

    /** Overrides {@code Thread$ThreadIdentifiers#next()}. */
    @Intercepts("java.lang.Thread$ThreadIdentifiers#next()")
    public static final class ThreadIdentifiersAdvice {
        public static final AtomicLong ID = new AtomicLong(10_000);

        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onExit(@Enter Node node, @Return(readOnly = false) long next) {
            if (node != null) {
                next = ID.getAndIncrement();
            }
        }
    }

    /** Overrides {@code java.lang.VirtualThread#schedule(Runnable, long, TimeUnit)}. */
    @Intercepts("java.lang.VirtualThread#schedule(Runnable,long,TimeUnit)")
    public static final class VThreadScheduleAdvice {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(
                @Enter Node node,
                @Argument(value = 0, readOnly = true) Runnable task,
                @Argument(value = 1, readOnly = true) long delay,
                @Argument(value = 2, readOnly = true) TimeUnit unit,
                @Return(readOnly = false) Future<?> future) {
            if (node != null) {
                future = node.scheduleAfterDelay(task, delay, unit);
            }
        }
    }

    /** Overrides {@code java.lang.VirtualThread#unblockVirtualThreads()}. */
    @Intercepts("java.lang.VirtualThread#unblockVirtualThreads()")
    public static final class UnblockVirtualThreadInterceptor {
        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit() {
            VirtualThreadUnblocker.unblockVirtualThreads();
        }
    }

    /** Overrides {@code java.util.concurrent.Executors#defaultThreadFactory()}. */
    @Intercepts("java.util.concurrent.Executors#defaultThreadFactory()")
    public static final class ExecutorsDefaultThreadFactoryAdvice {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void intercept(@Enter Node node, @Return(readOnly = false) ThreadFactory threadFactory)
                throws Throwable {
            if (node != null) {
                threadFactory = Thread.ofVirtual().factory();
            }
        }
    }

    /**
     * Intercepts {@link Thread#start()} to detect platform threads started inside a simulation context.
     *
     * <p>This is a diagnostic safety net: it logs a lifecycle warning when a platform thread is
     * started inside a simulation. Platform threads escape the simulation's deterministic scheduling
     * and are a source of non-determinism.
     *
     * <p>In practice this advice should never fire. All {@code Thread} subclasses — including those
     * from third-party libraries — are rewritten at build time to extend {@code SimulatorThread}
     * (a {@code VirtualThread}) and run under the deterministic scheduler. The JDK's
     * {@code VirtualThread-unblocker} platform thread is pre-initialized in {@code premain()} before
     * any simulation starts. This leaves only unexpected JDK-internal platform threads (e.g. from a
     * class initializer triggered during simulation) as potential triggers.
     */
    @Intercepts("java.lang.Thread#start()")
    public static final class ThreadStartAdvice {
        @OnMethodEnter
        @SuppressWarnings("MissingJavadocMethod")
        public static void onEnter(@Advice.This Thread self) {
            if (currentNodeOrNull() != null && !self.isVirtual()) {
                logPlatformThreadStart(self);
            }
        }
    }

    /**
     * Logs a lifecycle warning when a platform thread is started inside a simulation context.
     *
     * <p>Separate from the advice to avoid accessing package-private {@link Node} fields from
     * code inlined into {@code java.lang.Thread} (module {@code java.base}). Must be
     * {@code public} for the same module-access reason.
     */
    public static void logPlatformThreadStart(Thread platformThread) {
        var node = currentNodeOrNull();
        if (node == null) {
            return;
        }
        var simulator = node.simulator();
        node.logger()
                .logLifecycle("platform thread started", simulator.instant(), simulator.iteration())
                .withString("vhost", node.hostName)
                .withString("threadName", platformThread.getName())
                .withString("threadClass", platformThread.getClass().getName())
                .withString("caller", Thread.currentThread().getName())
                .log();
    }

    /**
     * Installs the {@code EXECUTOR_SUPPLIER} and {@code ON_CONSTRUCT} callback on
     * {@code SimulatorThread} (if present via {@code --patch-module}).
     *
     * <p>{@code SimulatorThread} lives in {@code java.base} and cannot access agent classes
     * like {@link Node} at runtime (module boundary). These callbacks use {@code java.base}
     * types ({@link Supplier}, {@link Consumer}) to bridge the gap: closures set via reflection
     * capture agent-side references ({@code node::scheduleNow}, {@code node.attachThread()}).
     *
     * <p>The {@code EXECUTOR_SUPPLIER} is called during each {@code SimulatorThread} constructor
     * to provide the per-node deterministic scheduler as the VirtualThread executor. This avoids
     * overwriting the final {@code scheduler} field after construction.
     *
     * <p>The {@code ON_CONSTRUCT} callback is invoked after each constructor completes to attach
     * the new thread to the simulation node (sets thread-locals, uncaught handler, classloader).
     *
     * @throws Simulator.SimulationError if {@code SimulatorThread} is not on the classpath
     *     (no {@code --patch-module}) or if the callback fields cannot be set
     */
    static void installSimulatorThreadCallback() {
        try {
            var stClass = Class.forName("java.lang.SimulatorThread");

            // EXECUTOR_SUPPLIER: provides per-node scheduler during construction
            var executorSupplierField = stClass.getField("EXECUTOR_SUPPLIER");
            Supplier<Executor> executorSupplier = () -> currentNodeOrNull()::scheduleNow;
            executorSupplierField.set(null, executorSupplier);

            // ON_CONSTRUCT: attaches thread to node after construction
            var onConstructField = stClass.getField("ON_CONSTRUCT");
            Consumer<Thread> callback = thread -> currentNodeOrNull().attachThread(thread);
            onConstructField.set(null, callback);
        } catch (ReflectiveOperationException e) {
            throw new Simulator.SimulationError("Failed to install SimulatorThread callback", e);
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(named("java.lang.ThreadBuilders"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(NewVirtualThreadAdvice.class).on(named("newVirtualThread"))))
                .asTerminalTransformation()
                .type(named("java.lang.ThreadBuilders$PlatformThreadBuilder"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(NewPlatformThreadAdvice.class).on(named("unstarted"))))
                .asTerminalTransformation()
                .type(named("java.lang.ThreadBuilders$PlatformThreadFactory"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(NewPlatformThreadAdvice.class).on(named("newThread"))))
                .asTerminalTransformation()
                .type(named("java.lang.Thread"))
                .transform((builder, _, _, _, _) -> builder.visit(
                                to(ThreadSetDaemonAdvice.class).on(named("setDaemon")))
                        .visit(to(ThreadStartAdvice.class).on(named("start").and(takesArguments(0)))))
                .asTerminalTransformation()
                .type(named("java.lang.Thread$ThreadIdentifiers"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(ThreadIdentifiersAdvice.class).on(named("next"))))
                .asTerminalTransformation()
                .type(named("java.lang.VirtualThread"))
                .transform((builder, _, _, _, _) -> builder.visit(to(VThreadScheduleAdvice.class)
                                .on(named("schedule").and(takesArguments(Runnable.class, long.class, TimeUnit.class))))
                        .method(named("unblockVirtualThreads"))
                        .intercept(to(UnblockVirtualThreadInterceptor.class).wrap(StubMethod.INSTANCE)))
                .asTerminalTransformation()
                .type(named("java.util.concurrent.Executors"))
                .transform((builder, _, _, _, _) -> builder.visit(
                        to(ExecutorsDefaultThreadFactoryAdvice.class).on(named("defaultThreadFactory"))))
                .asTerminalTransformation();
    }

    private ThreadsInterceptors() {
        // Prevent instantiation
    }
}
