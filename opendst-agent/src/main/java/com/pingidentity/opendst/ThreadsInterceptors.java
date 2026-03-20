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

import static com.pingidentity.opendst.Node.CURRENT_NODE;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.clearNext;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.compareAndSetOnWaitingList;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.getNext;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.getThreadLocal;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.takeVirtualThreadListToUnblock;
import static com.pingidentity.opendst.ThreadsInterceptors.Internals.unblock;
import static java.lang.Thread.ofVirtual;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.methodType;
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
        private static final MethodHandle NEW_THREAD;

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
                NEW_THREAD = privateLookupIn(Thread.class, lookup())
                        .findConstructor(
                                Thread.class,
                                methodType(
                                        void.class,
                                        ThreadGroup.class,
                                        String.class,
                                        int.class,
                                        Runnable.class,
                                        long.class));
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

        static Thread newThread(ThreadGroup group, String name, int characteristics, Runnable target, long stackSize) {
            try {
                return (Thread) NEW_THREAD.invoke(group, name, characteristics, target, stackSize);
            } catch (Throwable e) {
                throw new Simulator.SimulationError(e);
            }
        }

        static Thread getNext(Thread thread) {
            return (Thread) VTHREAD_NEXT.get(thread);
        }

        static void clearNext(Thread thread) {
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

        public static void unblock(Thread vthread) {
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
        /**
         * Unblocks all the virtual threads present on the waiting list.
         */
        public static void unblockVirtualThreads() {
            for (; ; ) {
                var vthread = takeVirtualThreadListToUnblock();
                while (vthread != null) {
                    var nextThread = getNext(vthread);
                    clearNext(vthread);
                    var nodeOrNull = (Node) getThreadLocal(vthread, CURRENT_NODE);
                    if (nodeOrNull == null) {
                        boolean changed = compareAndSetOnWaitingList(vthread, true, false);
                        assert changed;
                        unblock(vthread);
                    }
                    vthread = nextThread;
                }
            }
        }

        private VirtualThreadUnblocker() {}
    }

    /** Deterministic implementation of {@link Thread#Thread()}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod"})
    public static Thread newThread() {
        return CURRENT_NODE.get() != null ? ofVirtual().unstarted(() -> {}) : new Thread();
    }

    /** Deterministic implementation of {@link Thread#Thread(String)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod"})
    public static Thread newThread(String name) {
        return CURRENT_NODE.get() != null ? ofVirtual().name(name).unstarted(() -> {}) : new Thread(name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, String)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod"})
    public static Thread newThread(ThreadGroup group, String name) {
        return CURRENT_NODE.get() != null ? ofVirtual().name(name).unstarted(() -> {}) : new Thread(group, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(Runnable)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused"})
    public static Thread newThread(Runnable target) {
        return CURRENT_NODE.get() != null ? ofVirtual().unstarted(target) : new Thread(target);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused"})
    public static Thread newThread(ThreadGroup group, Runnable target) {
        return CURRENT_NODE.get() != null ? ofVirtual().unstarted(target) : new Thread(group, target);
    }

    /** Deterministic implementation of {@link Thread#Thread(Runnable, String)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused"})
    public static Thread newThread(Runnable target, String name) {
        return CURRENT_NODE.get() != null ? ofVirtual().name(name).unstarted(target) : new Thread(target, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused"})
    public static Thread newThread(ThreadGroup group, Runnable target, String name) {
        return CURRENT_NODE.get() != null ? ofVirtual().name(name).unstarted(target) : new Thread(group, target, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused"})
    public static Thread newThread(ThreadGroup group, Runnable target, String name, long stackSize) {
        return CURRENT_NODE.get() != null
                ? ofVirtual().name(name).unstarted(target)
                : new Thread(group, target, name, stackSize);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long, boolean)}. */
    @SuppressWarnings({"MissingJavadocMethod", "unused"})
    public static Thread newThread(
            ThreadGroup group, Runnable target, String name, long stackSize, boolean inheritThreadLocal) {
        return CURRENT_NODE.get() != null
                ? ofVirtual().name(name).unstarted(target)
                : new Thread(group, target, name, stackSize, inheritThreadLocal);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, String, int, Runnable, long)} . */
    @SuppressWarnings({"MissingJavadocMethod", "unused"})
    public static Thread newThread(
            ThreadGroup group, String name, int characteristics, Runnable target, long stackSize) {
        return CURRENT_NODE.get() != null
                ? ofVirtual().name(name).unstarted(target)
                : Internals.newThread(group, name, characteristics, target, stackSize);
    }

    /** Overrides {@code java.lang.ThreadBuilders#newVirtualThread(Executor, String, int, Runnable)}. */
    @Intercepts("java.lang.ThreadBuilders#newVirtualThread(Executor,String,int,Runnable)")
    public static final class NewVirtualThreadAdvice {
        @OnMethodEnter
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(value = 0, readOnly = false) Executor executor) {
            var node = CURRENT_NODE.get();
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

    /** Overrides {@link Thread#setDaemon(boolean)}. */
    @Intercepts("java.lang.Thread#setDaemon(boolean)")
    public static final class ThreadSetDaemonAdvice {
        @OnMethodEnter
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onEnter(@Argument(value = 0, readOnly = false) boolean on) {
            var node = CURRENT_NODE.get();
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
            return CURRENT_NODE.get();
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
            return CURRENT_NODE.get();
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
            return CURRENT_NODE.get();
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

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(named("java.lang.ThreadBuilders"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(NewVirtualThreadAdvice.class).on(named("newVirtualThread"))))
                .asTerminalTransformation()
                .type(named("java.lang.Thread"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(ThreadSetDaemonAdvice.class).on(named("setDaemon"))))
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
