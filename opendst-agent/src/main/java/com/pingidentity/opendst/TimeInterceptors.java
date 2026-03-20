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
import static com.pingidentity.opendst.Node.currentNodeOrThrow;
import static java.lang.System.setErr;
import static java.lang.System.setOut;
import static java.lang.Thread.currentThread;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.util.concurrent.Future.State.SUCCESS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.pingidentity.opendst.Simulator.SimulationError;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;
import net.bytebuddy.asm.Advice.Return;
import net.bytebuddy.implementation.StubMethod;

/**
 * Functional module for time simulation, scheduling, and real wall-clock access.
 */
public final class TimeInterceptors {

    /**
     * Manages the execution of tasks in the simulated environment.
     * Responsible for virtual time progression and task scheduling.
     */
    static final class Scheduler {

        private final Simulator simulator;
        private final ConsoleCapture logger;
        private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>();
        private Instant now;
        private long taskId;
        private boolean traceAuditorFailed;

        Scheduler(Instant startTime, Simulator simulator, ConsoleCapture logger) {
            this.now = startTime;
            this.simulator = simulator;
            this.logger = logger;
        }

        Instant now() {
            return now;
        }

        void run() {
            for (var task = tasks.poll(); task != null; task = tasks.poll()) {
                processTask(task);
            }
        }

        private void processTask(ScheduledTask task) {
            assert task.node != null;

            if (task.runAt.isBefore(now)) {
                simulator.reportInternalError(new SimulationError("Simulator has gone backward in time"));
            } else if (!task.skip) {
                now = task.runAt;
                executeTask(task);
                postTaskCleanup(task);
            }
        }

        private void executeTask(ScheduledTask task) {
            CURRENT_NODE.set(task.node);
            currentThread().setContextClassLoader(task.node.classLoader);
            setOut(task.node.console);
            setErr(task.node.console);
            simulator.hash(now, task.node.hostName, task.taskId);
            try {
                task.run();
                task.node.purgeAndUnblockVirtualThreads();
            } finally {
                CURRENT_NODE.remove();
            }
        }

        private void postTaskCleanup(ScheduledTask task) {
            if (!SUCCESS.equals(task.state())) {
                var throwable = task.exceptionNow();
                simulator.reportInternalError(new SimulationError("Task failed", throwable));
            }
            if (task.taskId % 100 == 0) {
                simulator.flushLogs();
                logger.flush();
            }
            try {
                logger.processLogs();
            } catch (Throwable e) {
                if (!traceAuditorFailed) {
                    traceAuditorFailed = true;
                    logger.logLifecycle("trace auditor exception", simulator.instant(), simulator.iteration())
                            .withString("cause", e.getMessage())
                            .withPOJO(
                                    "stacktrace",
                                    java.util.Arrays.stream(e.getStackTrace())
                                            .limit(10)
                                            .map(StackTraceElement::toString)
                                            .toList())
                            .log();
                }
            }
            simulator.checkNodesWaitingList(task.node);
        }

        Future<?> scheduleAfterDelay(Node node, Runnable task, long delay, TimeUnit unit) {
            return scheduleExactlyAt(node, task, now.plus(delay, unit.toChronoUnit()));
        }

        Future<?> scheduleExactlyAt(Node node, Runnable task, Instant at) {
            var current = currentNodeOrThrow();
            if (at.isBefore(now)) {
                simulator.reportInternalError(new SimulationError("Cannot schedule a task in the past"));
            }
            if (tasks.size() >= SimulationContext.MAX_TASKS) {
                simulator.reportInternalError(new SimulationError("Maximum task queue size reached"));
            }
            var queuedTask = new ScheduledTask(node, at, task, taskId++);
            tasks.add(queuedTask);
            return queuedTask;
        }
    }

    /** Represents a task waiting to be executed in the simulated environment. */
    private static final class ScheduledTask extends FutureTask<Void> implements Comparable<ScheduledTask> {
        private static final Comparator<ScheduledTask> COMPARATOR =
                Comparator.<ScheduledTask, Instant>comparing(q -> q.runAt).thenComparingLong(a -> a.taskId);

        final Node node;
        final Instant runAt;
        final long taskId;
        boolean skip;

        ScheduledTask(Node node, Instant runAt, Runnable task, long taskId) {
            super(task, null);
            this.node = node;
            this.runAt = runAt;
            this.taskId = taskId;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                skip = true;
            }
            return cancelled;
        }

        @Override
        public int compareTo(ScheduledTask other) {
            return COMPARATOR.compare(this, other);
        }
    }

    /**
     * Provides access to the real, non-simulated wall clock.
     */
    @SuppressWarnings("restricted")
    public static final class RealTime {
        private static final int CLOCK_MONOTONIC = 1;
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup STDLIB_LOOKUP = LINKER.defaultLookup();
        private static final MemoryLayout TIMEVAL_LAYOUT =
                structLayout(JAVA_LONG.withName("tv_sec"), JAVA_LONG.withName("tv_usec"));
        private static final long TV_SEC_OFFSET = TIMEVAL_LAYOUT.byteOffset(groupElement("tv_sec"));
        private static final long TV_USEC_OFFSET = TIMEVAL_LAYOUT.byteOffset(groupElement("tv_usec"));
        private static final MemoryLayout TIMESPEC_LAYOUT =
                structLayout(JAVA_LONG.withName("tv_sec"), JAVA_LONG.withName("tv_nsec"));
        private static final long TS_SEC_OFFSET = TIMESPEC_LAYOUT.byteOffset(groupElement("tv_sec"));
        private static final long TS_NSEC_OFFSET = TIMESPEC_LAYOUT.byteOffset(groupElement("tv_nsec"));

        private static final MethodHandle GETTIMEOFDAY;
        private static final MethodHandle CLOCK_GETTIME;

        static {
            var getTimeOfDayAddr = STDLIB_LOOKUP
                    .find("gettimeofday")
                    .orElseThrow(() -> new SimulationError("Cannot find symbol: gettimeofday"));
            GETTIMEOFDAY = LINKER.downcallHandle(getTimeOfDayAddr, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

            var clockGetTimeAddr = STDLIB_LOOKUP
                    .find("clock_gettime")
                    .orElseThrow(() -> new SimulationError("Cannot find symbol: clock_gettime"));
            CLOCK_GETTIME = LINKER.downcallHandle(clockGetTimeAddr, FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        }

        public static long currentTimeMillis() {
            try (var arena = Arena.ofConfined()) {
                var timevalSegment = arena.allocate(TIMEVAL_LAYOUT);
                int ret = (int) GETTIMEOFDAY.invokeExact(timevalSegment, MemorySegment.NULL);
                if (ret != 0) throw new SimulationError("gettimeofday failed: " + ret);
                return SECONDS.toMillis(timevalSegment.get(JAVA_LONG, TV_SEC_OFFSET))
                        + MICROSECONDS.toMillis(timevalSegment.get(JAVA_LONG, TV_USEC_OFFSET));
            } catch (Throwable e) {
                throw new SimulationError(e);
            }
        }

        public static long nanoTime() {
            try (var arena = Arena.ofConfined()) {
                var timespecSegment = arena.allocate(TIMESPEC_LAYOUT);
                int ret = (int) CLOCK_GETTIME.invokeExact(CLOCK_MONOTONIC, timespecSegment);
                if (ret != 0) throw new SimulationError("clock_gettime failed: " + ret);
                return SECONDS.toNanos(timespecSegment.get(JAVA_LONG, TS_SEC_OFFSET))
                        + timespecSegment.get(JAVA_LONG, TS_NSEC_OFFSET);
            } catch (Throwable e) {
                throw new SimulationError(e);
            }
        }

        private RealTime() {}
    }

    /**
     * Overrides {@link System#currentTimeMillis()} and {@link System#nanoTime()}.
     */
    @Intercepts("java.lang.System#currentTimeMillis()")
    @Intercepts("java.lang.System#nanoTime()")
    public static final class TimeAdvice {
        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void intercept(@Advice.Origin Method method, @Return(readOnly = false) long out) {
            var node = CURRENT_NODE.get();
            out = node != null
                    ? "currentTimeMillis".equals(method.getName()) ? node.currentTimeMillis() : node.nanoTime()
                    : "currentTimeMillis".equals(method.getName()) ? RealTime.currentTimeMillis() : RealTime.nanoTime();
        }
    }

    /** Overrides {@link Clock#currentInstant()}. */
    @Intercepts("java.time.Clock#currentInstant()")
    public static final class ClockCurrentInstantAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return CURRENT_NODE.get();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Node node, @Return(readOnly = false) Instant instant) {
            if (node != null) {
                instant = node.instant();
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(named("java.lang.System"))
                .transform((builder, _, _, _, _) -> builder.method(
                                named("currentTimeMillis").or(named("nanoTime")))
                        .intercept(to(TimeAdvice.class).wrap(StubMethod.INSTANCE)))
                .asTerminalTransformation()
                .type(named("java.time.Clock"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(ClockCurrentInstantAdvice.class).on(named("currentInstant"))))
                .asTerminalTransformation();
    }

    private TimeInterceptors() {}
}
