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
import static com.pingidentity.opendst.Node.currentNodeOrThrow;
import static com.pingidentity.opendst.SimulationContext.MAX_TASKS;
import static com.pingidentity.opendst.Simulator.ExitReason.INTERNAL_ERROR;
import static com.pingidentity.opendst.Simulator.ExitReason.PLAN_OK;
import static com.pingidentity.opendst.SimulatorAgent.AGENT_PROPERTY;
import static java.lang.Boolean.getBoolean;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Future.State.CANCELLED;
import static java.util.concurrent.Future.State.SUCCESS;

import com.pingidentity.opendst.Plan.Segment;
import com.pingidentity.opendst.sdk.TraceAuditor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.jr.ob.JSON;
import tools.jackson.jr.ob.JacksonJrExtension;
import tools.jackson.jr.ob.api.ExtensionContext;
import tools.jackson.jr.ob.api.ReaderWriterProvider;
import tools.jackson.jr.ob.api.ValueReader;
import tools.jackson.jr.ob.impl.JSONReader;

/**
 * Implements a deterministic execution environment.
 */
public final class Simulator {

    enum ExitReason {
        PLAN_OK(0),
        INTERNAL_ERROR(1);

        final int code;

        ExitReason(int code) {
            this.code = code;
        }
    }

    @SuppressWarnings("serial")
    public static final class SimulationError extends VirtualMachineError {
        SimulationError(String message) {
            super(message);
        }

        SimulationError(Throwable cause) {
            super(cause);
        }

        SimulationError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @SuppressWarnings("serial")
    public static class SystemExitError extends Error {
        public final int exitCode;

        public SystemExitError(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    // Visible for testing
    static final Set<String> REDIRECT_CONSTRUCTORS_OF = Set.of("java/lang/Thread");

    static final Set<String> REDIRECT_STATIC_METHODS_OF =
            Set.of("com/pingidentity/opendst/sdk/Signals", "com/pingidentity/opendst/sdk/Assert");

    static final Instant START_TIME = Instant.ofEpochSecond(1445385600);

    private final SimulationContext context;
    private final Plan plan;
    private final StateHasher hasher;
    private boolean ready;

    private Simulator(Plan plan, TraceAuditor traceAuditor) throws IOException {
        this.plan = requireNonNull(plan);

        // Build all components — each takes only its direct dependencies
        var random = new RandomInterceptors.Source(this, plan.segments());
        var faults = plan.faults() != null ? plan.faults() : new Faults.Config();
        this.hasher = new StateHasher();
        var logger = new ConsoleCapture(this, traceAuditor, System.out);
        var network = new NetworkInterceptors(this);
        var faultInjector = new Faults.Injector(this, faults);
        var scheduler = new Scheduler(START_TIME, logger);

        // Assemble the immutable context last — passed only to Node
        this.context = new SimulationContext(this, scheduler, random, faults, network, faultInjector, logger);

        logger.logLifecycle("started", START_TIME, 0).log();
    }

    public RandomInterceptors.Source random() {
        return context.random();
    }

    public boolean isReady() {
        return ready;
    }

    void onReady() {
        ready = true;
    }

    public static void runSimulation(Callable<Void> scenario) {
        runSimulation(scenario, _ -> {});
    }

    public static void runSimulation(Callable<Void> scenario, TraceAuditor traceAuditor) {
        requireNonNull(scenario);
        requireNonNull(traceAuditor);
        checkSimulationState();
        try {
            var plan = JSON.builder()
                    .register(new JacksonJrExtension() {
                        @Override
                        protected void register(ExtensionContext ctxt) {
                            ctxt.appendProvider(new ReaderWriterProvider() {
                                @Override
                                public ValueReader findValueReader(JSONReader readContext, Class<?> type) {
                                    if (Duration.class.isAssignableFrom(type)) {
                                        return new ValueReader(Duration.class) {
                                            @Override
                                            public Object read(JSONReader reader, JsonParser p)
                                                    throws JacksonException {
                                                var value = p.getString();
                                                return value != null ? Duration.parse(value) : Duration.ZERO;
                                            }
                                        };
                                    }
                                    return null;
                                }
                            });
                        }
                    })
                    .build()
                    .beanFrom(Plan.class, System.in);
            requireNonNull(plan);
            validatePlan(plan);
            currentThread().setName("OpenDST Simulator Carrier Thread");
            var simulator = new Simulator(plan, traceAuditor);
            executeSimulation(simulator, scenario);
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            getRuntime().halt(1);
        }
    }

    private static void checkSimulationState() {
        if (currentNodeOrNull() != null) {
            throw new IllegalStateException(format(
                    "The carrier thread '%s' is already running a simulation",
                    currentThread().getName()));
        } else if (!getBoolean(AGENT_PROPERTY)) {
            throw new IllegalStateException("The simulation cannot start because the simulator agent is not present");
        }
    }

    private static void validatePlan(Plan plan) {
        if (plan.segments().isEmpty()) {
            System.err.println("The provided plan is invalid: at least one segment is required");
            getRuntime().halt(1);
        }
        Segment previous = null;
        for (var segment : plan.segments()) {
            if (previous != null && previous.iteration() >= segment.iteration()) {
                System.err.println("The provided plan is invalid: segments are overlapping and/or are out of order");
                getRuntime().halt(1);
            }
            previous = segment;
        }
    }

    private static void executeSimulation(Simulator simulator, Callable<Void> scenario) {
        try {
            simulator.run(scenario);
        } catch (Throwable e) {
            simulator.reportInternalError(new SimulationError("Simulation execution failed", e));
        }
    }

    void hash(Object... objects) {
        hasher.update(objects);
    }

    /**
     * Called by {@link RandomInterceptors.Source} at each segment boundary. Snapshots the current hash,
     * emits a {@code "segment-completed"} lifecycle signal, and checks the departing segment's
     * expected hash. If the expected hash is non-zero and differs from the actual hash, the
     * simulation exits with a non-determinism report.
     *
     * @param departingSegment the segment that just completed
     */
    void checkSegmentHash(Segment departingSegment) {
        int actualHash = hasher.getHash();
        if (departingSegment.hash() == 0) {
            // Nothing to check
        } else if (departingSegment.hash() == actualHash) {
            context.logger()
                    .logLifecycle("segment-completed", instant(), iteration())
                    .withNumber("hash", actualHash)
                    .log();
        } else {
            context.logger()
                    .logLifecycle("non-determinism detected", instant(), iteration())
                    .withNumber("expectedHash", departingSegment.hash())
                    .withNumber("actualHash", actualHash)
                    .log();
            exitSimulation(INTERNAL_ERROR);
        }
    }

    void exitSimulation(ExitReason reason) {
        int actualHash = hasher.getHash();
        // Check for run-level non-determinism before emitting the stopped signal
        if (!INTERNAL_ERROR.equals(reason) && plan.hash() != 0 && plan.hash() != actualHash) {
            context.logger()
                    .logLifecycle("non-determinism detected", instant(), iteration())
                    .withNumber("expectedHash", plan.hash())
                    .withNumber("actualHash", actualHash)
                    .log();
        }
        var finalLog =
                context.logger().logLifecycle("stopped", instant(), iteration()).withNumber("hash", actualHash);
        try {
            finalLog.log();
            context.logger().flush();
        } catch (Throwable e) {
            e.printStackTrace(context.logger().getOut());
        } finally {
            try {
                // Let the orchestrator kill this process once it received the "stopped" signal
                sleep(60_000);
            } catch (InterruptedException _) {
            }
            getRuntime().exit(reason.code);
        }
    }

    /**
     * Reports an internal error by emitting a lifecycle signal and then terminating the simulation.
     *
     * @param cause the error that triggered the internal error
     */
    void reportInternalError(SimulationError cause) {
        context.logger()
                .logLifecycle("internal error", instant(), iteration())
                .withString("cause", cause.getMessage())
                .withPOJO(
                        "stacktrace",
                        stream(cause.getStackTrace())
                                .limit(10)
                                .map(StackTraceElement::toString)
                                .toList())
                .log();
        exitSimulation(INTERNAL_ERROR);
    }

    public static void startNode(String hostName, String ipAddress, Callable<Void> bootstrap) throws IOException {
        requireNonNull(hostName);
        requireNonNull(ipAddress);
        requireNonNull(bootstrap);
        var current = currentNodeOrThrow();
        new Node(current.context, getSystemClassLoader(), hostName, ipAddress).startNode(bootstrap);
    }

    Instant instant() {
        return context.scheduler().now();
    }

    long iteration() {
        return context.random().iteration();
    }

    public static void startNode(
            String hostName, InetAddress ipAddress, ClassLoader classLoader, Method main, String[] args) {
        requireNonNull(hostName);
        requireNonNull(ipAddress);
        requireNonNull(classLoader);
        requireNonNull(main);
        requireNonNull(args);

        var current = currentNodeOrThrow();
        try {
            var node = new Node(current.context, classLoader, hostName, ipAddress.getHostAddress());
            node.startNode(() -> {
                try {
                    main.invoke(null, (Object) args);
                    return null;
                } catch (Throwable e) {
                    if (e instanceof InvocationTargetException ite) {
                        if (ite.getCause() != null && ite.getCause() instanceof SystemExitError) {
                            return null;
                        }
                    }
                    throw e;
                }
            });
        } catch (Throwable e) {
            throw new IllegalArgumentException("Unable to start node " + hostName, e);
        }
    }

    private void run(Callable<Void> scenario) throws Throwable {
        var bootstrapNode = new Node(context, getSystemClassLoader(), "simulator", "127.0.0.1");
        bootstrapNode.startNode(scenario);
        context.scheduler().run();
        // When the scheduler's run loop exits but threads are still alive, it means all remaining
        // threads are blocked (waiting on I/O, locks, etc.). This is a normal simulation outcome
        // — not an error — since the plan simply ran out of runnable tasks.
        exitSimulation(PLAN_OK);
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    /**
     * Manages the execution of tasks in the simulated environment.
     * Responsible for virtual time progression and task scheduling.
     */
    final class Scheduler {
        private final ConsoleCapture logger;
        private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>();
        private Instant now;
        private long taskId;

        Scheduler(Instant startTime, ConsoleCapture logger) {
            this.now = startTime;
            this.logger = logger;
        }

        Instant now() {
            return now;
        }

        void run() {
            for (var task = tasks.poll(); task != null; task = tasks.poll()) {
                if (task.runAt.isBefore(now)) {
                    reportInternalError(new SimulationError("Simulator has gone backward in time"));
                } else {
                    now = task.runAt;
                    var result = task.execute();
                    if (!Set.of(SUCCESS, CANCELLED).contains(result)) {
                        reportInternalError(new SimulationError("Task failed", task.exceptionNow()));
                    }
                    logger.flush();
                    checkNodesWaitingList(task.node);
                }
            }
        }

        private void checkNodesWaitingList(Node node) {
            context.network().nodes().values().forEach(n -> n.checkNoThreadOnWaitingList(node));
        }

        Future<?> scheduleExactlyAt(Node node, Runnable task, Instant at) {
            if (at.isBefore(now)) {
                reportInternalError(new SimulationError("Cannot schedule a task in the past"));
            }
            if (tasks.size() >= MAX_TASKS) {
                reportInternalError(new SimulationError("Maximum task queue size reached"));
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

        private final Node node;
        private final Instant runAt;
        private final long taskId;

        ScheduledTask(Node node, Instant runAt, Runnable task, long taskId) {
            super(task, null);
            this.node = requireNonNull(node);
            this.runAt = requireNonNull(runAt);
            this.taskId = taskId;
        }

        @Override
        public int compareTo(ScheduledTask other) {
            return COMPARATOR.compare(this, other);
        }

        State execute() {
            node.execute(this);
            return state();
        }
    }

    void uncaughtExceptionHandler(Node node, Thread thread, Throwable throwable) {
        context.logger()
                .logLifecycle(
                        "uncaught exception", instant(), node.context.random().iteration())
                .withString("vhost", node.hostName)
                .withString("thread", thread.getName())
                .withPOJO("exception", throwable)
                .log();
    }

    /**
     * Incrementally builds a hash of the execution trace to detect non-determinism.
     * Objects must have deterministic hashCode() (e.g. Strings, Numbers, Records).
     */
    private static final class StateHasher {
        private int hash = 0;

        synchronized void update(Object... objects) {
            for (var o : objects) {
                hash = 31 * hash + Objects.hashCode(o);
            }
        }

        synchronized int getHash() {
            return hash;
        }
    }
}
