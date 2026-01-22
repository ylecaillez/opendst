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

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.methodType;

import com.pingidentity.opendst.Simulator.SimulationError;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/** Exposes internal methods of {@link VirtualThread}. */
final class VirtualThreadInternals {
    public static final Class<?> VTHREAD_CLASS;
    /** {@link VirtualThread#next}. */
    private static final VarHandle VTHREAD_NEXT;
    /** {@link VirtualThread#onWaitingList}. */
    private static final VarHandle VTHREAD_ON_WAITING_LIST;
    /** {@link VirtualThread#onWaitingList}. */
    private static final MethodHandle VTHREAD_CAS_ON_WAITING_LIST;
    /** {@link VirtualThread#takeVirtualThreadListToUnblock()}. */
    private static final MethodHandle VTHREAD_TAKE_LIST_TO_UNBLOCK;
    /** {@link VirtualThread#unblock()}. */
    private static final MethodHandle VTHREAD_UNBLOCK;
    /** {@link ThreadLocal#get(Thread)}. */
    private static final MethodHandle THREAD_LOCAL_GET;
    /** {@link ThreadLocal#set(Thread, Object)}. */
    private static final MethodHandle THREAD_LOCAL_SET;
    /** {@link Thread#Thread(ThreadGroup, String, int, Runnable, long)}. */
    private static final MethodHandle NEW_THREAD;

    static {
        try {
            VTHREAD_CLASS = privateLookupIn(Thread.class, lookup()).findClass("java.lang.VirtualThread");
            VTHREAD_NEXT = privateLookupIn(VTHREAD_CLASS, lookup()).findVarHandle(VTHREAD_CLASS, "next", VTHREAD_CLASS);
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
        } catch (Exception e) {
            throw new SimulationError("Unable to find virtual thread internal APIs", e);
        }
    }

    /** Blocks until a virtual thread has exited a monitor and needs to be re-scheduled. */
    static Thread takeVirtualThreadListToUnblock() {
        try {
            return (Thread) VTHREAD_CLASS.cast(VTHREAD_TAKE_LIST_TO_UNBLOCK.invoke());
        } catch (Throwable e) {
            throw new SimulationError("Unable to get the next virtual thread to unblock", e);
        }
    }

    static Thread newThread(ThreadGroup group, String name, int characteristics, Runnable target, long stackSize) {
        try {
            return (Thread) NEW_THREAD.invoke(group, name, characteristics, target, stackSize);
        } catch (Throwable e) {
            throw new SimulationError(e);
        }
    }

    /** Exposes {@link VirtualThread#next}. */
    static Thread getNext(Thread thread) {
        if (!thread.isVirtual()) {
            throw new SimulationError("getNext() cannot be performed on a non virtual thread");
        }
        return (Thread) VTHREAD_NEXT.get(thread);
    }

    /** Exposes {@link VirtualThread#next}. */
    static void clearNext(Thread thread) {
        if (!thread.isVirtual()) {
            throw new SimulationError("Attempt to clear a non virtual thread");
        }
        VTHREAD_NEXT.set(thread, null);
    }

    /** Exposes {@link VirtualThread#onWaitingList}. */
    @SuppressWarnings("checkstyle:JavadocMethod")
    static boolean isOnWaitingList(Thread thread) {
        return (boolean) VTHREAD_ON_WAITING_LIST.get(thread);
    }

    /** Exposes {@link VirtualThread#onWaitingList}. */
    @SuppressWarnings("checkstyle:JavadocMethod")
    static boolean compareAndSetOnWaitingList(Thread thread, boolean expected, boolean update) {
        try {
            return (boolean) VTHREAD_CAS_ON_WAITING_LIST.invoke(thread, expected, update);
        } catch (Throwable e) {
            throw new SimulationError(e);
        }
    }

    /** Exposes {@link ThreadLocal#get(Thread)}. */
    @SuppressWarnings("checkstyle:JavadocMethod")
    static Object getThreadLocal(Thread thread, ThreadLocal<?> threadLocal) {
        try {
            return THREAD_LOCAL_GET.invoke(threadLocal, thread);
        } catch (Throwable e) {
            throw new SimulationError(e);
        }
    }

    /** Exposes {@link ThreadLocal#set(Thread, Object)}. */
    @SuppressWarnings("checkstyle:JavadocMethod")
    static Object setThreadLocal(Thread thread, ThreadLocal<?> threadLocal, Object value) {
        try {
            return THREAD_LOCAL_SET.invoke(threadLocal, thread, value);
        } catch (Throwable e) {
            throw new SimulationError(e);
        }
    }

    /** Exposes {@link VirtualThread#unblock()}. */
    @SuppressWarnings("checkstyle:JavadocMethod")
    public static void unblock(Thread vthread) {
        try {
            VTHREAD_UNBLOCK.invoke(vthread);
        } catch (Throwable e) {
            throw new SimulationError(e);
        }
    }

    private VirtualThreadInternals() {
        // Prevent instantiation
    }
}
