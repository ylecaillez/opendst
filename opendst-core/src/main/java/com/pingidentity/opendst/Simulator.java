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
import static com.pingidentity.opendst.Simulator.ExitReason.PLAN_OK;
import static com.pingidentity.opendst.SimulatorAgent.AGENT_PROPERTY;
import static java.lang.Boolean.getBoolean;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.walk;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

import com.pingidentity.opendst.Plan.Segment;
import com.pingidentity.opendst.api.LogMonitor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.jr.ob.JSON;

/**
 * Implements a deterministic execution environment.
 */
public final class Simulator {

    enum ExitReason {
        PLAN_OK(0, "success"),
        PLAN_FAILED(1, "failure"),
        FLAKY(2, "flaky"),
        INTERNAL_ERROR(3, "internal error");

        final int code;
        final String message;

        ExitReason(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    public static final class SimulationError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;

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

    public static class SystemExitError extends Error {
        private static final long serialVersionUID = 1L;

        public final int exitCode;

        public SystemExitError(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    // Visible for testing
    static final Set<String> REDIRECT_CONSTRUCTORS_OF = Set.of(
            "java/lang/Thread",
            "java/net/Socket",
            "java/net/ServerSocket",
            "java/io/FileInputStream",
            "java/io/FileOutputStream",
            "java/io/RandomAccessFile");

    static final Set<String> REDIRECT_STATIC_METHODS_OF = Set.of(
            "java/nio/file/Files",
            "com/pingidentity/opendst/api/Signals",
            "com/pingidentity/opendst/api/Assert",
            "com/pingidentity/opendst/api/Simulator");

    static final Instant START_TIME = Instant.ofEpochSecond(1445385600);

    private final SimulationContext context;
    private final Plan plan;
    private boolean ready;

    private Simulator(Plan plan, LogMonitor logMonitor) throws IOException {
        this.plan = requireNonNull(plan);
        var random = new Randomness.Source(this, plan.segments());
        var scheduler = new Time.Scheduler(START_TIME);
        var faults = plan.faults() != null ? plan.faults() : new Faults.Config();

        // Step 1: Create basic context
        this.context = new SimulationContext(this, scheduler, random, faults);

        // Step 2: Initialize components that need the context
        var network = new Network(context);
        var faultInjector = new Faults.Injector(context);
        var logger = new ConsoleCapture(this, logMonitor, System.out);
        var lock = new ReentrantLock();

        // Step 3: Wire everything back into the context
        context.setNetwork(network);
        context.setFaultInjector(faultInjector);
        context.setLogger(logger);
        context.setLock(lock);

        // Step 4: Final scheduler setup
        scheduler.setContext(context);

        logger.logLifecycle("started", START_TIME, 0).log();
    }

    public Randomness.Source random() {
        return context.random();
    }

    public boolean isReady() {
        return ready;
    }

    void onReady() {
        ready = true;
    }

    public Set<String> nodeNames() {
        return Set.copyOf(context.network().nodes().keySet());
    }

    public static void runSimulation(Callable<Void> scenario) {
        runSimulation(scenario, _ -> {});
    }

    public static void runSimulation(Callable<Void> scenario, LogMonitor logMonitor) {
        requireNonNull(scenario);
        requireNonNull(logMonitor);
        checkSimulationState();
        try {
            var plan = JSON.std.beanFrom(Plan.class, System.in);
            requireNonNull(plan);
            validatePlan(plan);
            currentThread().setName("OpenDST Simulator Carrier Thread");
            var simulator = new Simulator(plan, logMonitor);
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
            simulator.exitSimulation(ExitReason.PLAN_FAILED, e);
        }
    }

    void hash(Object... objects) {
        context.hasher().update(objects);
    }

    void exitSimulation(ExitReason reason, Throwable cause) {
        Node.CURRENT.remove();
        flushLogs();
        int actualHash = context.hasher().getHash();
        var exitReason = reason;
        if (plan.hash() != 0 && plan.hash() != actualHash) {
            exitReason = ExitReason.FLAKY;
        }

        var log = context.logger()
                .logLifecycle("stopped", instant(), iteration())
                .withString("reason", exitReason.message)
                .withNumber("lastRandom", context.random().last)
                .withNumber("hash", actualHash);
        if (cause == null) {
            log.withString("cause", "<unknown>").log();
        } else {
            var actualCause =
                    cause instanceof InvocationTargetException && cause.getCause() != null ? cause.getCause() : cause;
            log.withString("cause", actualCause.getClass().getSimpleName() + ": " + actualCause.getMessage())
                    .withPOJO(
                            "trace",
                            stream(actualCause.getStackTrace())
                                    .limit(10)
                                    .map(StackTraceElement::toString)
                                    .toList())
                    .log();
        }
        try {
            context.logger().flush();
        } catch (Exception e) {
            e.printStackTrace(context.logger().getOut());
        } finally {
            context.logger().getOut().close();
            getRuntime().halt(exitReason.code);
        }
    }

    void flushLogs() {
        context.network().nodes().values().forEach(Node::flush);
    }

    void checkNodesWaitingList(Node node) {
        context.network().nodes().values().forEach(n -> n.checkNoThreadOnWaitingList(node));
    }

    public static void startNode(String hostName, String ipAddress, Callable<Void> bootstrap) throws IOException {
        requireNonNull(hostName);
        requireNonNull(ipAddress);
        requireNonNull(bootstrap);
        var current = currentNodeOrThrow();
        new Node(current.context, getSystemClassLoader(), hostName, ipAddress).startNode(bootstrap);
    }

    public static Path workingDirectory() {
        return currentNodeOrThrow().workingDirectory();
    }

    /**
     * Creates and starts a deployment of services within the simulation.
     *
     * <p>This is a convenience method equivalent to creating a {@link Deployment} and calling
     * its {@link Deployment#deploy()} method. Use this when testing real distributed applications
     * with classloader-isolated nodes.
     *
     * @param images   the list of images to use
     * @param services the list of services to deploy
     * @throws Exception if an error occurs during deployment
     * @see Deployment#deploy()
     */
    public static void deploy(
            java.util.List<com.pingidentity.opendst.api.Deployment.Image> images,
            java.util.List<com.pingidentity.opendst.api.Deployment.Service> services)
            throws Exception {
        Deployment.deployment(images, services).deploy();
    }

    Instant instant() {
        return context.scheduler().now();
    }

    long iteration() {
        return context.random().iteration();
    }

    public static void startNode(
            String hostName,
            InetAddress ipAddress,
            ClassLoader classLoader,
            Path filesystemSourceDir,
            Method main,
            String[] args) {
        requireNonNull(hostName);
        requireNonNull(ipAddress);
        requireNonNull(classLoader);
        requireNonNull(main);
        requireNonNull(args);

        var current = currentNodeOrThrow();
        try {
            var node = new Node(current.context, classLoader, hostName, ipAddress.getHostAddress());
            if (filesystemSourceDir != null) {
                initFileSystem(filesystemSourceDir, node.workingDirectory());
            }
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

    private static void initFileSystem(Path source, Path target) throws IOException {
        requireNonNull(source);
        requireNonNull(target);
        try (var stream = walk(source)) {
            var paths = stream.toList();
            for (var path : paths) {
                var relative = source.relativize(path);
                var destination = target.resolve(relative);
                if (isDirectory(path)) {
                    createDirectories(destination);
                } else {
                    copy(path, destination);
                }
            }
        } catch (IOException e) {
            if (!(e instanceof NoSuchFileException)) {
                throw e;
            }
        }
    }

    private void run(Callable<Void> scenario) throws Throwable {
        var bootstrapNode = new Node(context, getSystemClassLoader(), "simulator", "127.0.0.1");
        Node.CURRENT.set(bootstrapNode);
        bootstrapNode.startNode(scenario);
        context.scheduler().run();
        flushLogs();
        if (anyThreadAlive()) {
            exitSimulation(PLAN_OK, new SimulationError("All remaining threads are blocked"));
        } else {
            exitSimulation(PLAN_OK, null);
        }
    }

    private boolean anyThreadAlive() {
        for (var node : context.network().nodes().values()) {
            for (var thread : node.virtualThreads) {
                if (thread.isAlive()) {
                    return true;
                }
            }
        }
        return false;
    }

    void uncaughtExceptionHandler(Node node, Thread thread, Throwable throwable) {
        context.logger()
                .logLifecycle(
                        "Uncaught exception", instant(), node.context.random().iteration())
                .withString("vhost", node.hostName)
                .withString("thread", thread.getName())
                .withPOJO("exception", throwable)
                .log();
        exitSimulation(ExitReason.PLAN_FAILED, throwable);
    }

    public Future<?> scheduleAfterDelay(Runnable task, long delay, TimeUnit unit) {
        var node = currentNodeOrThrow();
        return context.scheduler()
                .scheduleExactlyAt(node, task, context.scheduler().now().plus(delay, unit.toChronoUnit()));
    }
}
