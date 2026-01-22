/*
 * Copyright 2024-2025 Ping Identity Corporation
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

import static com.pingidentity.opendst.SimulatorAgent.AGENT_PROPERTY;
import static com.pingidentity.opendst.VirtualThreadInternals.clearNext;
import static com.pingidentity.opendst.VirtualThreadInternals.compareAndSetOnWaitingList;
import static com.pingidentity.opendst.VirtualThreadInternals.getNext;
import static com.pingidentity.opendst.VirtualThreadInternals.getThreadLocal;
import static com.pingidentity.opendst.VirtualThreadInternals.isOnWaitingList;
import static com.pingidentity.opendst.VirtualThreadInternals.setThreadLocal;
import static com.pingidentity.opendst.VirtualThreadInternals.takeVirtualThreadListToUnblock;
import static com.pingidentity.opendst.VirtualThreadInternals.unblock;
import static java.lang.Boolean.getBoolean;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.Long.toHexString;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.ofVirtual;
import static java.lang.Thread.onSpinWait;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.walkFileTree;
import static java.time.Duration.ofSeconds;
import static java.util.Locale.ROOT;
import static java.util.concurrent.Future.State.SUCCESS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.io.Serial;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

/**
 * Implements a deterministic execution environment.
 * <p>
 * To provide a deterministic execution environment, the non-deterministic Java API are transparently replaced by
 * simulated version which provide deterministic execution. Given the same inputs, the execution history of the
 * scenario provided to {@link #runSimulation(Callable)}} will always be the same.
 * <p>
 * While most of the work to replace non-deterministic API is done transparently thanks to the {@link SimulatorAgent}
 * there are still some limitations which might require the tested application to be modified in order to behave
 * deterministically:
 * <ul>
 *     <li>extends {@link Thread}: Inheriting from {@link Thread} is not supported. The application code must be
 *     rewritten to use composition rather than inheritance</li>
 *     <li>Network: Socket are not compatible with the simulated network</li>*
 *     <li>Blocking JNI methods: Carrier-thread are pinned while invoking a JNI method. As a result, the simulator
 *     will be blocked until the JNI method returns</li>
 * </ul>
 */
public final class Simulator implements AutoCloseable {
    /**
     * Thrown when an unrecoverable error has been encountered during the simulation.
     * <p>
     * Some low-level resource might have leaks (like VirtualThread, Continuation, ...) or be in an
     * undetermined state. At this point the JVM should be considered unusable and must be exited.
     */
    public static final class SimulationError extends VirtualMachineError {
        @Serial
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

    /** The source of randomness used by the simulator and everything running inside the simulator. */
    public static final class SourceOfRandomness extends Random {
        @Serial
        private static final long serialVersionUID = 1L;

        private SourceOfRandomness(long seed) {
            super(seed);
        }

        /**
         * {@return the next random number}
         *
         * @param bits size in bits of the random number to generate
         */
        public int nextBits(int bits) {
            return next(bits);
        }

        @Override
        protected int next(int bits) {
            return super.next(bits);
        }
    }

    /** Thrown by {@link Runtime#exit(int)} after stopping all the machine's threads. */
    public static class SystemExitError extends Error {
        @Serial
        private static final long serialVersionUID = 1L;
        private final int exitCode;

        /**
         * Constructs a new {@link SystemExitError} instance with the specified exit code.
         *
         * @param exitCode the exitCode code provided to {@link System#exit(int)}
         */
        public SystemExitError(int exitCode) {
            this.exitCode = exitCode;
        }

        /** {@return The status code provided to System.exit()} */
        public int exitCode() {
            return exitCode;
        }
    }

    public static final String SIMULATOR_RUN_DIR = "simulator.run-dir";
    private static final long SIMULATOR_SEED = 42;
    // Visible for testing
    static final Set<String> REDIRECT_CONSTRUCTORS_OF = Set.of("java/lang/Thread",
                                                               "java/net/Socket",
                                                               "java/net/ServerSocket");
    // 2015-10-21, back to the future.
    private static final long START_TIME_SECONDS = 1445385600;

    private record UncaughtException(Machine machine, Thread thread, Throwable throwable) {
    }

    private static final ThreadLocal<Machine> MACHINE = new ThreadLocal<>();
    private final Map<InetAddress, String> addressToName = new HashMap<>();
    private final Map<String, Node> nameToNode = new HashMap<>();
    final Lock lock = new ReentrantLock();
    final SourceOfRandomness random = new SourceOfRandomness(SIMULATOR_SEED);
    /** Contains the tasks waiting to be executed in the simulated environment. */
    private final PriorityQueue<QueuedTask> taskQueue = new PriorityQueue<>();
    /** Keeps track of the latest uncaught exception which occurred in a simulator-carried virtual threads. */
    private UncaughtException uncaughtException;
    /** The simulated wall-clock time. */
    private final long nowSeconds = START_TIME_SECONDS;
    final Path runBaseDir;
    /** Represents the offset in nanoseconds from the simulated wall-clock time. */
    private long nanoTime;
    /** The task id is used to break ties in the scheduling of tasks with the same launch time. */
    private long taskId;
    /** Whether this simulator has been closed. */
    private boolean closed;

    private Simulator() throws IOException {
        this.runBaseDir = Path.of(getProperty(SIMULATOR_RUN_DIR, "target/simulation"))
                              .resolve(toHexString(SIMULATOR_SEED));
        Files.createDirectories(runBaseDir);
    }

    /**
     * Runs the provided scenario into a deterministic execution environment.
     *
     * @param scenario The scenario to run in the simulated environment.
     */
    public static void runSimulation(Callable<Void> scenario) throws ExecutionException {
        runSimulation("simulator", "127.0.0.1", scenario);
    }

    /**
     * Runs the provided scenario into a deterministic execution environment.
     *
     * @param hostName  Name of the host executing the provided scenario
     * @param ipAddress IP address of the host executing the provided scenario
     * @param scenario  The scenario to run in the simulated environment
     */
    public static void runSimulation(String hostName, String ipAddress, Callable<Void> scenario)
            throws ExecutionException {
        if (MACHINE.get() != null) {
            throw new IllegalStateException(
                    format("The carrier thread '%s' is already running a simulation", currentThread().getName()));
        } else if (!getBoolean(AGENT_PROPERTY)) {
            throw new IllegalStateException("The simulation cannot start because the simulator agent is not present");
        }
        var threadName = currentThread().getName();
        currentThread().setName("Simulator Carrier Thread");
        /* var out = System.out;
        setOut(new PrintStream(OutputStream.nullOutputStream()));
        var err = System.err;
        setErr(new PrintStream(OutputStream.nullOutputStream())); */
        try (var simulator = new Simulator()) {
            var node = new Node(simulator, getSystemClassLoader(), "simulator", "127.0.0.1");
            MACHINE.set(node);
            node.startNode(scenario);
            simulator.runLoop();
        } catch (IOException e) {
            throw new SimulationError("The simulation has failed to start due to an I/O error", e);
        } finally {
            MACHINE.remove();
            currentThread().setName(threadName);
            /* setOut(out);
            setErr(err); */
        }
    }

    /**
     * Starts a new node in the simulation environment.
     * <p>
     * The node is identified by the given host name and IP address, and is initialized using the provided bootstrap
     * runnable. This method should be called from within a running simulation.
     *
     * @param hostName  the name of the host to start
     * @param ipAddress the IP address of the host
     * @param bootstrap the bootstrap runnable to initialize the node
     */
    public static void startNode(String hostName, String ipAddress, Callable<Void> bootstrap) throws IOException {
        new Node(machineOrThrow().simulator, getSystemClassLoader(), hostName, ipAddress).startNode(bootstrap);
    }

    /** {@return the working directory of the current node} */
    public static Path workingDirectory() {
        return machineOrThrow().workingDirectory();
    }

    /**
     * Starts a new node in the simulation environment.
     * <p>
     * This method initializes a new node with the specified host name, IP address, class loader, and main method.
     * The node is started by invoking the provided main method with the given arguments.
     * <p>
     * This method should be called from within a running simulation.
     *
     * @param hostName            the name of the host to start
     * @param ipAddress           the IP address of the host
     * @param classLoader         the class loader to use for the node
     * @param filesystemSourceDir the directory containing the initial content which will be copied into the node's
     *                            filesystem.
     * @param main                the main method to invoke for starting the node
     * @param args                the arguments to pass to the main method
     * @throws IllegalArgumentException if the node cannot be started
     * @throws IllegalStateException    if the method is not called from within a running simulation.
     */
    public static void startNode(String hostName,
                                 InetAddress ipAddress,
                                 ClassLoader classLoader,
                                 Path filesystemSourceDir,
                                 Method main,
                                 String[] args) {
        var simulator = machineOrThrow().simulator;
        try {
            var node = new Node(simulator, classLoader, hostName, ipAddress.getHostAddress());
            if (filesystemSourceDir != null) {
                initFileSystem(filesystemSourceDir, node.workingDirectory);
            }
            node.startNode(() ->  {
                try {
                    main.invoke(null, (Object) args);
                    return null;
                } catch (InvocationTargetException e) {
                    if (e.getCause() != null && e.getCause() instanceof SystemExitError) {
                        return null;
                    }
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to start node " + hostName, e);
        }
    }

    @SuppressWarnings("NullableProblems")
    private static void initFileSystem(Path source, Path target) throws IOException {
        walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                createDirectories(target.resolve(source.relativize(dir)));
                return CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                copy(file, target.resolve(source.relativize(file)));
                return CONTINUE;
            }
        });
    }

    private static Machine machineOrThrow() {
        var machine = machineOrNull();
        if (machine == null) {
            throw new IllegalStateException(
                format("This operation cannot be performed from the thread '%s' as it is not part of a simulation",
                       currentThread().getName()));
        }
        return machine;
    }

    /**
     * Stops the node with the specified host name in the simulation environment.
     * <p>
     * This method should be called from within a running simulation. If the node exists, it will be stopped.
     *
     * @param hostName the name of the host to stop
     */
    public static void stopNode(String hostName) {
        machineOrThrow().simulator.stopNode0(hostName);
    }

    private void stopNode0(String hostName) {
        var node = nameToNode.get(hostName.toLowerCase());
        if (node != null) {
            node.stopNode();
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private void runLoop() {
        if (closed) {
            throw new IllegalStateException("This simulator has been closed");
        }

        QueuedTask maybeTask;
        while ((maybeTask = taskQueue.poll()) != null && uncaughtException == null) {
            if (maybeTask.nanos < nanoTime) {
                throw new SimulationError("Simulator has gone backward in time");
            }
            nanoTime = maybeTask.nanos;
            currentThread().setContextClassLoader(maybeTask.machine.classLoader);
            MACHINE.set(maybeTask.machine);
            maybeTask.run();
            if (!SUCCESS.equals(maybeTask.state())) {
                throw new SimulationError(maybeTask.exceptionNow());
            }
            // To keep determinism, unblock all the simulator-carried virtual threads that have exited an object monitor
            // during the previous task execution before the next iteration.
            maybeTask.machine.purgeAndUnblockVirtualThreads();
            var machine = maybeTask.machine;
            // TODO: this check is great a detecting sources of non-determinism but it is slow. It should probably be
            //  conditioned (e.g. doing it once every hundred runs)
            nameToNode.values().forEach(node -> node.checkNoThreadOnWaitingList(machine));
        }

        if (uncaughtException != null) {
            var exception = uncaughtException;
            uncaughtException = null;
            throw new SimulationError(format("Uncaught exception on node '%s' in thread '%s'",
                                              exception.machine().hostName, exception.thread().getName()),
                                      exception.throwable());
        } else if (allThreadsAreStuck()) {
            throw new SimulationError(
                    "The simulation terminated because all the remaining threads are stuck and can't make progress");
        }
    }

    private boolean allThreadsAreStuck() {
        boolean stuck = false;
        for (var node : nameToNode.values()) {
            for (var thread : node.virtualThreads) {
                if (thread.isAlive()) {
                    var stack = new IllegalStateException(
                            format("Node '%s': the thread '%s' is stuck", node.hostName, thread.getName()));
                    stack.setStackTrace(thread.getStackTrace());
                    stack.printStackTrace(System.err);
                    stuck = true;
                }
            }
        }
        return stuck;
    }

    /**
     * The default uncaught exception handler used by every simulator-carried virtual thread created by this simulator.
     * When invoked it'll register the uncaught exception into this simulator which will cause it to throws an
     * {@link AssertionError} at the end of the current run-loop.
     *
     * @param machine   The machine where the exception has been thrown
     * @param thread    The simulator-carried virtual thread which exited because of the uncaught exception
     * @param throwable The uncaught exception
     */
    void uncaughtExceptionHandler(Machine machine, Thread thread, Throwable throwable) {
        if (uncaughtException == null && !(throwable instanceof SystemExitError)) {
            uncaughtException = new UncaughtException(machine, thread, throwable);
        }
    }

    private Future<?> scheduleExactlyAt(Machine machine, Runnable task, long atNanos) {
        if (closed) {
            // At this point, the outcome of the simulation is not deterministic anymore.
            throw new SimulationError("Cannot schedule a task since this simulator is closed");
        } else if (machineOrNull() == null) {
            // We defer the exception and still execute the task as this case happens when we try to dump the
            // stacktrace of all the virtual threads.
            deferException(machine, new SimulationError(
                    format("The thread '%s' is not allowed to schedule task as it is not part of this simulation",
                           currentThread().getName())));
            task.run();
            return new FutureTask<>(() -> { }, null);
        } else if (atNanos < nanoTime) {
            throw new SimulationError("Cannot schedule a task in the past");
        }
        var queuedTask = new QueuedTask(machine, atNanos, task, taskId++);
        var added = taskQueue.add(queuedTask);
        assert added;
        return queuedTask;
    }

    /**
     * Keeps track of an error when this one cannot be thrown immediately because we're invoked by the JDK possibly
     * from an internal thread (e.g: unparker, unblocker, ...). The error will then be thrown at the next run-loop
     * iteration.
     */
    private void deferException(Machine machine, Throwable t) {
        uncaughtExceptionHandler(machine, currentThread(), t);
    }

    /** {@return The Simulation attached to this thread or null if this thread is not part of a simulation}. */
    public static Machine machineOrNull() {
        var machine = MACHINE.get();
        if (machine == null) {
            MACHINE.remove();
        }
        return machine;
    }

    private final class QueuedTask extends FutureTask<Void> implements Comparable<QueuedTask> {
        private final Machine machine;
        private final long nanos;
        private final long taskId;

        private QueuedTask(Machine machine, long launchTime, Runnable task, long taskId) {
            super(task, null);
            this.machine = machine;
            this.nanos = launchTime;
            this.taskId = taskId;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                // No need to execute it
                taskQueue.remove(this);
            }
            return cancelled;
        }

        @Override
        public int compareTo(QueuedTask other) {
            return nanos > other.nanos ? 1 : nanos < other.nanos ? -1 : Long.compare(taskId, other.taskId);
        }
    }

    /** Deterministic implementation of {@link Socket#Socket()}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket() throws IOException {
        return socketFactory().createSocket();
    }

    private static SocketFactory socketFactory() {
        var machine = machineOrNull();
        return machine != null ? machine.socketFactory() : SocketFactory.getDefault();
    }

    /** Deterministic implementation of {@link Socket#Socket(InetAddress, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(InetAddress address, int port) throws IOException {
        return socketFactory().createSocket(address, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, boolean)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused", "removal" })
    public static Socket newSocket(InetAddress address, int port, boolean stream) throws IOException {
        if (stream) {
            throw new UnsupportedOperationException("Cannot create a stream socket");
        }
        return newSocket(address, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, InetAddress, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return socketFactory().createSocket(address, port, localAddress, localPort);
    }

    /** Deterministic implementation of {@link Socket#Socket(Proxy)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(Proxy proxy) {
        var simulator = machineOrNull();
        if (simulator != null) {
            throw new UnsupportedOperationException("Cannot create a proxy socket");
        }
        return new Socket(proxy);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(String host, int port) throws IOException {
        return socketFactory().createSocket(host, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, boolean)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused", "removal" })
    public static Socket newSocket(String host, int port, boolean stream) throws IOException {
        if (stream) {
            throw new UnsupportedOperationException("Cannot create a stream socket");
        }
        return socketFactory().createSocket(host, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, InetAddress, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
        return socketFactory().createSocket(host, port, localAddress, localPort);
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket()}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket() throws IOException {
        return serverSocketFactory().createServerSocket();
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket(int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket(int port) throws IOException {
        return serverSocketFactory().createServerSocket(port);
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket(int, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket(int port, int backlog) throws IOException {
        return serverSocketFactory().createServerSocket(port, backlog);
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket(int, int, InetAddress)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
        return serverSocketFactory().createServerSocket(port, backlog, bindAddr);
    }

    private static ServerSocketFactory serverSocketFactory() {
        var machine = machineOrNull();
        return machine != null ? machine.serverSocketFactory() : ServerSocketFactory.getDefault();
    }

    /** Deterministic implementation of {@link Executors#defaultThreadFactory()}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public ThreadFactory executorsDefaultThreadFactory() {
        return ofVirtual().factory();
    }

    /** Deterministic implementation of {@link Thread#Thread()}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod" })
    public static Thread newThread() {
        var machine = machineOrNull();
        return machine != null ? machine.newThread() : new Thread();
    }

    /** Deterministic implementation of {@link Thread#Thread(String)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod" })
    public static Thread newThread(String name) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(name) : new Thread(name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, String)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod" })
    public static Thread newThread(ThreadGroup group, String name) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(group, name) : new Thread(group, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(Runnable)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public static Thread newThread(Runnable target) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(target) : new Thread(target);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public static Thread newThread(ThreadGroup group, Runnable target) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(group, target) : new Thread(group, target);
    }

    /** Deterministic implementation of {@link Thread#Thread(Runnable, String)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public static Thread newThread(Runnable target, String name) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(target, name) : new Thread(target, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public static Thread newThread(ThreadGroup group, Runnable target, String name) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(group, target, name) : new Thread(group, target, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public static Thread newThread(ThreadGroup group, Runnable target, String name, long stackSize) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(group, target, name, stackSize)
                               : new Thread(group, target, name, stackSize);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long, boolean)}. */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public static Thread newThread(ThreadGroup group, Runnable target, String name, long stackSize,
                                   boolean inheritThreadLocal) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(group, target, name, stackSize, inheritThreadLocal)
                               : new Thread(group, target, name, stackSize, inheritThreadLocal);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, String, int, Runnable, long)} . */
    @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
    public static Thread newThread(ThreadGroup group, String name, int characteristics, Runnable target,
                                   long stackSize) {
        var machine = machineOrNull();
        return machine != null ? machine.newThread(group, target, name, stackSize)
                               : VirtualThreadInternals.newThread(group, name, characteristics, target, stackSize);
    }

    /**
     * Replaces {@code VirtualThread#unblockVirtualThreads()} which processes virtual threads exiting object monitor.
     * <p>
     * As a result of JEP-491, object monitors are no more a cause of thread pinning. When a virtual thread exits an
     * object monitor, it is added to an internal linked-list: the waiting list. These virtual threads are waiting for
     * being re-scheduled. All this is happening at the C++ level. The linked-list is exposed on the Java side by
     * {@code VirtualThread#takeVirtualThreadListToUnblock()} which is processed by dedicated platform thread:
     * "VirtualThread-unblocker". This method blocks until at least one virtual thread has been added to the
     * linked-list. The VirtualThread-unblocker thread will then invoke {@code VirtualThread#unblock()} on each
     * virtual thread present in this list. As a result, all the virtual threads in the waiting list are rescheduled.
     * <p>
     * The VirtualThread-unblocker is a platform thread which does not expose any guarantee regarding the processing of
     * the list. It is not possible to know deterministically when a virtual thread present in the list will effectively
     * be rescheduled.
     * <p>
     * This {@link VirtualThreadUnblocker#unblockVirtualThreads()} is a reimplementation of the original
     * {@code VirtualThread#unblockVirtualThreads()}. It still runs on the same "VirtualThread-unblocker" and performs
     * the same tasks as the original method but, to not break the determinism, it will only unblock platform-carried
     * virtual threads. The simulator-carried virtual threads are present on this waiting-list will be ignored by
     * this method and will be processed after each task invocation in the {@link #runLoop()}.
     */
    public static final class VirtualThreadUnblocker {
        /**
         * Unblocks all the virtual threads present on the waiting list.
         * <p>
         * This method blocks until at least one virtual thread has been added to the waiting list. It will then
         * process all the platform-carried virtual threads present in the waiting list. The simulator-carried virtual
         * threads are ignored by this method and are unblocked in the {@link #runLoop()} to preserve determinism.
         */
        public static void unblockVirtualThreads() {
            for (;;) {
                var vthread = takeVirtualThreadListToUnblock();
                while (vthread != null) {
                    var nextThread = getNext(vthread);
                    clearNext(vthread);
                    var machineOrNull = getThreadLocal(vthread, MACHINE);
                    if (machineOrNull == null) {
                        boolean changed = compareAndSetOnWaitingList(vthread, true, false);
                        assert changed;
                        unblock(vthread);
                    }
                    vthread = nextThread;
                }
            }
        }

        private VirtualThreadUnblocker() {
            // Prevent instantiation
        }
    }

    boolean registerDns(String hostName, Node host) {
        var lowerCaseHostName = hostName.toLowerCase(ROOT);
        if (nameToNode.putIfAbsent(lowerCaseHostName, host) == null) {
            host.inetAddresses().forEach(address -> {
                if (!address.isLoopbackAddress()) {
                    addressToName.put(address, lowerCaseHostName);
                }
            });
            return true;
        }
        return false;
    }

    void unregisterDns(String hostName) {
        var hostname = hostName.toLowerCase(ROOT);
        var host = nameToNode.remove(hostname);
        if (host != null) {
            host.inetAddresses().forEach(address -> addressToName.remove(address, hostname));
        }
    }

    String lookupByAddress(InetAddress addr) throws UnknownHostException {
        var hostName = addressToName.get(addr);
        if (hostName == null) {
            throw new UnknownHostException(addr.getHostAddress());
        }
        return hostName;
    }

    private Node lookupHostByAddress(InetAddress addr) throws UnknownHostException {
        var host = nameToNode.get(lookupByAddress(addr));
        if (host == null) {
            throw new UnknownHostException(addr.getHostAddress());
        }
        return host;
    }

    Stream<InetAddress> lookupByName(String name) throws UnknownHostException {
        var resolvedHost = nameToNode.get(name.toLowerCase());
        if (resolvedHost == null) {
            throw new UnknownHostException(name);
        }
        return resolvedHost.inetAddresses();
    }

    SocketImpl route(InetAddress from, InetAddress addr, int port) throws UnknownHostException, NoRouteToHostException {
        if (from.isLoopbackAddress() && !addr.isLoopbackAddress()) {
            throw new NoRouteToHostException();
        }
        return lookupHostByAddress(addr).route(from, addr, port);
    }

    /** Provides deterministic implementation of JDK API. */
    public abstract static class Machine {
        public static final Duration SHUTDOWN_GRACE_TIME = ofSeconds(30);
        final Simulator simulator;
        private final ClassLoader classLoader;
        final List<Thread> virtualThreads = new ArrayList<>();
        private final long salt32l;
        private final boolean reverse;
        private final ToLongFunction<Random> defaultSchedulingJitter = r -> r.nextLong(1, 10_000);
        final Path workingDirectory;
        final Random random;
        final String hostName;
        private final List<Thread> shutdownHooks = new ArrayList<>();
        private final CompletableFuture<Integer> shutdown = new CompletableFuture<>();
        private boolean stopped;

        Machine(Simulator simulator, ClassLoader classLoader, String hostName) throws IOException {
            this.simulator = simulator;
            this.classLoader = classLoader;
            var servicePath = simulator.runBaseDir.resolve("services", hostName);
            this.workingDirectory = servicePath.resolve("fs");
            createDirectories(workingDirectory);
            this.random = simulator.random;
            this.hostName = hostName.toLowerCase();
            this.salt32l = simulator.random.nextLong() & 0xFFFF_FFFFL;
            this.reverse = (salt32l & 1) == 0;
        }

        /** Deterministic implementation of {@link InetAddress#getLocalHost()}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract InetAddress getLocalHost();

        /** Deterministic implementation of {@link InetAddressResolver#lookupByAddress(byte[])}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract String lookupByAddress(InetAddress byAddress) throws UnknownHostException;

        /** Deterministic implementation of {@link InetAddressResolver#lookupByName(String, LookupPolicy)}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract Stream<InetAddress> lookupByName(String host) throws UnknownHostException;

        /** Deterministic source of randomness. */
        public SourceOfRandomness sourceOfRandomness() {
            return simulator.random;
        }

        /** Deterministic implementation of {@link SocketFactory}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract SocketFactory socketFactory();

        /** Deterministic implementation of {@link ServerSocketFactory}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract ServerSocketFactory serverSocketFactory();

        /**
         * Attaches the provided thread to this machine. Once attached, invocation of {@link #machineOrNull()} from that
         * thread will return this machine instance.
         *
         * @param thread A newly created thread
         */
        public void attachThread(Thread thread) {
            assert thread.isVirtual();
            virtualThreads.add(thread);
            setThreadLocal(thread, MACHINE, this);
            thread.setUncaughtExceptionHandler(this::uncaughtExceptionHandler);
            thread.setContextClassLoader(classLoader);
        }

        void startNode(Callable<Void> bootstrap) {
            scheduleNow(() -> ofVirtual().name(format("start-node-%s", hostName)).start(() -> {
                try {
                    bootstrap.call();
                } catch (Throwable e) {
                    uncaughtExceptionHandler(currentThread(), e);
                }
            }));
        }

        /** Stops this node. Behaves as if the node had invoked {@link Runtime#exit}. */
        void stopNode() {
            if (!stopped) {
                // Scheduling a new thread while this node is already being stopped would result in infinite loop as
                // the termination of this thread might lead purgeAndUnblockVirtualThreads() to invoke this stopNode()
                // method again.
                scheduleNow(() -> ofVirtual().name(format("stop-node-%s", hostName)).start(() -> exit(0)));
            }
        }

        /**
         * Overrides {@link Runtime#exit(int)}.
         *
         * @param status exit status.
         */
        public void exit(int status) {
            if (stopped) {
                return;
            }
            stopped = true;
            shutdownHooks.forEach(Thread::start);
            try {
                if (!virtualThreads.remove(currentThread())) {
                    throw new SimulationError(
                            format("exit() has been invoked from thread '%s' which is not part of the node '%s'",
                                   currentThread().getName(), hostName));
                }
                if (waitUntilAllThreadExits()) {
                    // End of the grace period, interrupt all remaining threads
                    virtualThreads.forEach(Thread::interrupt);
                    waitUntilAllThreadExits();
                }
                for (var thread : virtualThreads) {
                    if (thread.isAlive()) {
                        var stack = new IllegalStateException("Stack trace");
                        stack.setStackTrace(thread.getStackTrace());
                        throw new SimulationError(format("The thread '%s' from node '%s' cannot be stopped.",
                                                         thread.getName(), hostName), stack);
                    }
                }
                shutdown.complete(status);
            } catch (InterruptedException e) {
                throw new SimulationError(e);
            } finally {
                simulator.unregisterDns(hostName);
            }
        }

        private boolean waitUntilAllThreadExits() throws InterruptedException {
            long deadlineMs = currentTimeMillis() + SHUTDOWN_GRACE_TIME.toMillis();
            boolean someThreadStillAlive;
            do {
                someThreadStillAlive = false;
                for (var thread : new ArrayList<>(virtualThreads)) {
                    if (thread.isAlive() && !thread.join(ofSeconds(1))) {
                        someThreadStillAlive = true;
                    }
                }
            } while (someThreadStillAlive && currentTimeMillis() < deadlineMs);
            return someThreadStillAlive;
        }

        /**
         * Iterates over all the simulator-carried virtual threads, removing the terminated threads and unblocking
         * the threads which exited a monitor. This is costly and a better alternative would be to leverage
         * {@code ThreadContainers#onExit()} and an implementation of
         * {@link VirtualThread#takeVirtualThreadListToUnblock()} which would return immediately if the list is empty
         * rather than blocking. Unfortunately this implies modifying the JDK code.
         */
        boolean purgeAndUnblockVirtualThreads() {
            boolean unblocked = false;
            boolean alive = false;
            for (var it = virtualThreads.iterator(); it.hasNext();) {
                var thread = it.next();
                alive |= thread.isAlive();
                if (TERMINATED.equals(thread.getState())) {
                    it.remove();
                } else if (isOnWaitingList(thread)) {
                    // Wait until this thread has been detached from the waiting list by the unblocked-thread
                    while (getNext(thread) != null) {
                        onSpinWait();
                    }
                    if (!compareAndSetOnWaitingList(thread, true, false)) {
                        // Should never happen. One possible explanation is that this thread has been unblocked by the
                        // VirtualThread-unblocker. Proof that this run is no more deterministic.
                        throw new SimulationError(
                                format("Thread '%s' is no more present on the waiting-list. Determinism is broken",
                                       thread.getName()));
                    }
                    unblocked = true;
                    unblock(thread);
                }
            }
            if (!alive) {
                stopNode();
            }
            return unblocked;
        }

        void checkNoThreadOnWaitingList(Machine machine) {
            if (machine != this) {
                for (var thread : virtualThreads) {
                    if (isOnWaitingList(thread)) {
                        var ise = new IllegalStateException("Stack trace");
                        ise.setStackTrace(thread.getStackTrace());
                        throw new SimulationError(
                                format("Thread '%s' from machine '%s' is unexpectedly present on the waiting-list",
                                       thread.getName(), hostName), ise);
                    }
                }
            }
        }

        /**
         * Executes the provided runnable in the context of this simulator.
         * <p>
         * The runnable will be scheduled with a small jitter to simulate the non-deterministic scheduling of the
         * virtual threads.
         *
         * @param runnable The runnable to execute in the context of this simulator
         */
        public void scheduleNow(Runnable runnable) {
            simulator.scheduleExactlyAt(this,
                                        runnable,
                                        nanoTime() + defaultSchedulingJitter.applyAsLong(simulator.random));
        }

        /**
         * Schedules the provided runnable to be executed at the specified time in the future.
         * <p>
         * The runnable will be scheduled with a small jitter to simulate the non-deterministic scheduling of the
         * virtual threads.
         *
         * @param runnable The runnable to execute in the context of this simulator
         * @param delay    The delay after which the runnable should be executed
         * @param unit     The time unit of the delay
         * @return A {@link Future} representing pending completion of the task
         */
        public Future<?> scheduleAfterDelay(Runnable runnable, long delay, TimeUnit unit) {
            var delayNanos = unit.toNanos(delay) + defaultSchedulingJitter.applyAsLong(simulator.random);
            return simulator.scheduleExactlyAt(this, runnable, Math.addExact(nanoTime(), delayNanos));
        }

        /** Deterministic implementation of {@code java.util.ImmutableCollections#REVERSE}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public final boolean immutableCollectionsReverse() {
            return reverse;
        }

        /** Deterministic implementation of {@code java.util.ImmutableCollections#SALT32L}. */
        public final long immutableCollectionsSalt32l() {
            return salt32l;
        }

        /** {@return the current simulated wall-clock time in nanoseconds.} */
        public long nanoTime() {
            return simulator.nanoTime;
        }

        /** {@return the current simulated wall-clock time in milliseconds.} */
        public long currentTimeMillis() {
            return SECONDS.toMillis(simulator.nowSeconds) + NANOSECONDS.toMillis(simulator.nanoTime);
        }

        /** Deterministic implementation of {@link Runtime#addShutdownHook(Thread)}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public void addShutdownHook(Thread hook) {
            shutdownHooks.add(hook);
        }

        /** Deterministic implementation of {@link Runtime#removeShutdownHook(Thread)}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public boolean removeShutdownHook(Thread hook) {
            return shutdownHooks.remove(hook);
        }

        /** Deterministic implementation of {@link Executors#defaultThreadFactory()}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public ThreadFactory executorsDefaultThreadFactory() {
            return ofVirtual().factory();
        }

        /** Deterministic implementation of {@link Thread#Thread()}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread() {
            return ofVirtual().unstarted(() -> { });
        }

        /** Deterministic implementation of {@link Thread#Thread(String)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(String name) {
            return ofVirtual().name(name).unstarted(() -> { });
        }

        /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, String)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(ThreadGroup group, String name) {
            return ofVirtual().name(name).unstarted(() -> { });
        }

        /** Deterministic implementation of {@link Thread#Thread(Runnable)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(Runnable target) {
            return ofVirtual().unstarted(target);
        }

        /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(ThreadGroup group, Runnable target) {
            return ofVirtual().unstarted(target);
        }

        /** Deterministic implementation of {@link Thread#Thread(Runnable, String)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(Runnable target, String name) {
            return ofVirtual().name(name).unstarted(target);
        }

        /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(ThreadGroup group, Runnable target, String name) {
            return ofVirtual().name(name).unstarted(target);
        }

        /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(ThreadGroup group, Runnable target, String name, long stackSize) {
            return ofVirtual().name(name).unstarted(target);
        }

        /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long, boolean)}. */
        @SuppressWarnings({ "checkstyle:JavadocMethod", "unused" })
        public Thread newThread(ThreadGroup group,
                                Runnable target,
                                String name,
                                long stackSize,
                                boolean inheritThreadLocal) {
            return ofVirtual().name(name).unstarted(target);
        }

        /**
         * Registers an uncaught exception for the specified thread in the context of this simulator.
         * <p>
         * This method is called when a thread terminates due to an uncaught exception. The exception is stored
         * and will be thrown as a {@link Simulator.SimulationError} at the end of the current run-loop.
         *
         * @param thread    the thread that encountered the uncaught exception
         * @param throwable the uncaught exception
         */
        public void uncaughtExceptionHandler(Thread thread, Throwable throwable) {
            simulator.uncaughtExceptionHandler(this, thread, throwable);
        }

        /** {@return the working directory of the current node} */
        public Path workingDirectory() {
            return workingDirectory;
        }
    }
}
