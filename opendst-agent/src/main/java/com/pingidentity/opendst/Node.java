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

import static com.pingidentity.opendst.Simulator.ExitReason.INTERNAL_ERROR;
import static com.pingidentity.opendst.Threads.Internals.compareAndSetOnWaitingList;
import static com.pingidentity.opendst.Threads.Internals.getNext;
import static com.pingidentity.opendst.Threads.Internals.isOnWaitingList;
import static com.pingidentity.opendst.Threads.Internals.setThreadLocal;
import static java.lang.String.format;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.ofVirtual;
import static java.lang.Thread.onSpinWait;
import static java.net.InetAddress.getLoopbackAddress;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketImpl;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import com.pingidentity.opendst.NodeSocketImpl.Binding;

/**
 * Represents an isolated execution environment (a node) within the simulation.
 * <p>
 * A Node provides its own IP address, class loader, and deterministic implementations of JDK APIs.
 */
public final class Node {
    public static final ThreadLocal<Node> CURRENT_NODE = new ThreadLocal<>();

    final SimulationContext context;
    final String hostName;
    final ClassLoader classLoader;
    final PrintStream console;
    final List<Thread> virtualThreads = new ArrayList<>();
    final List<Thread> shutdownHooks = new ArrayList<>();
    final long salt32l;
    final boolean reverse;
    final ToLongFunction<Random> defaultSchedulingJitter = r -> r.nextLong(1, 10_000);
    final CompletableFuture<Integer> shutdown = new CompletableFuture<>();
    boolean stopped;

    private final InetAddress localHost;
    private final NodeInterfaces netInterfaces;

    private static final int MAX_VIRTUAL_THREADS = 1000;

    Node(SimulationContext context, ClassLoader classLoader, String hostName, String localIpAddress)
            throws IOException {
        this.context = requireNonNull(context);
        this.classLoader = requireNonNull(classLoader);
        requireNonNull(hostName);
        if (hostName.isBlank()) {
            throw new IllegalArgumentException("hostName cannot be blank");
        }
        assert localIpAddress != null;

        this.hostName = hostName.toLowerCase();
        this.salt32l = context.random().nextLong() & 0xFFFF_FFFFL;
        this.reverse = (salt32l & 1) == 0;
        this.console = new PrintStream(context.logger().newLogWriter(hostName), true);

        this.localHost = InetAddress.ofLiteral(localIpAddress);
        this.netInterfaces = new NodeInterfaces(localHost);
        context.network().registerDns(hostName, this);
    }

    /**
     * {@return The Node attached to this thread}.
     * @throws IllegalStateException if this thread is not part of a simulation.
     */
    static Node currentNodeOrThrow() {
        var node = CURRENT_NODE.get();
        if (node == null) {
            throw new IllegalStateException(format(
                    "This operation cannot be performed from the thread '%s' as it is not part of a simulation",
                    currentThread().getName()));
        }
        return node;
    }

    void flush() {
        console.flush();
    }

    /** Must be {@code public} — called from advice inlined into {@code java.net.InetAddress}. */
    public InetAddress getLocalHost() {
        return localHost;
    }

    /** Must be {@code public} — called from advice inlined into {@code java.time.Clock}. */
    public Instant instant() {
        return context.scheduler().now();
    }

    /**
     * Must be {@code public} — called from advice inlined into {@code java.util.Random},
     * {@code java.security.SecureRandom}, {@code java.util.concurrent.ThreadLocalRandom}, etc.
     */
    public Randomness.Source random() {
        return context.random();
    }

    ConsoleCapture logger() {
        return context.logger();
    }

    Faults.Injector faultInjector() {
        return context.faultInjector();
    }

    Network network() {
        return context.network();
    }

    Faults.Config faults() {
        return context.faults();
    }

    Simulator simulator() {
        return context.simulator();
    }

    Stream<InetAddress> inetAddresses() {
        return Stream.of(localHost);
    }

    @SuppressWarnings({"deprecation", "removal"})
    SocketImpl newSocketImpl(boolean isServer) {
        return new NodeSocketImpl(this, isServer);
    }

    @SuppressWarnings({"deprecation", "removal"})
    Binding bindSocket(InetAddress address, int port, SocketImpl socket, boolean reuseAddress)
            throws BindException {
        return netInterfaces.bind(address, port, socket, reuseAddress);
    }

    /**
     * Attaches the provided thread to this node.
     *
     * <p>Must be {@code public} — called from advice inlined into {@code java.lang.ThreadBuilders}.
     *
     * @param thread A newly created thread
     */
    public void attachThread(Thread thread) {
        requireNonNull(thread);
        if (!thread.isVirtual()) {
            throw new IllegalArgumentException("Only virtual threads can be attached to a node");
        }
        if (virtualThreads.size() >= MAX_VIRTUAL_THREADS) {
            context.simulator()
                    .exitSimulation(
                            INTERNAL_ERROR,
                            new Simulator.SimulationError(
                                    "Max number of virtual threads reached for machine '" + hostName + "'"));
        }
        virtualThreads.add(thread);
        setThreadLocal(thread, CURRENT_NODE, this);
        thread.setUncaughtExceptionHandler(this::uncaughtExceptionHandler);
        thread.setContextClassLoader(classLoader);
    }

    /**
     * Executes the provided scenario on this node.
     *
     * @param scenario The scenario to execute
     */
    void startNode(Callable<Void> scenario) {
        requireNonNull(scenario);
        var originalNode = CURRENT_NODE.get();
        CURRENT_NODE.set(this);
        try {
            ofVirtual().name(hostName + "-main").start(() -> {
                try {
                    scenario.call();
                    shutdown.complete(0);
                } catch (Throwable e) {
                    if (e instanceof Simulator.SystemExitError exitError) {
                        shutdown.complete(exitError.exitCode);
                    } else {
                        uncaughtExceptionHandler(currentThread(), e);
                    }
                } finally {
                    stopped = true;
                    context.network().unregisterDns(hostName);
                    shutdownHooks.forEach(Thread::start);
                    shutdownHooks.forEach(hook -> {
                        try {
                            hook.join();
                        } catch (InterruptedException e) {
                            currentThread().interrupt();
                        }
                    });
                }
            });
        } finally {
            CURRENT_NODE.set(originalNode);
        }
    }

    void checkNoThreadOnWaitingList(Node node) {
        requireNonNull(node);
        if (node != this) {
            for (var thread : virtualThreads) {
                if (isOnWaitingList(thread)) {
                    var ise = new IllegalStateException("Stack trace");
                    ise.setStackTrace(thread.getStackTrace());
                    context.simulator()
                            .exitSimulation(
                                    INTERNAL_ERROR,
                                    new Simulator.SimulationError(
                                            format(
                                                    "Thread '%s' from machine '%s' is unexpectedly present on the waiting-list of"
                                                            + " machine '%s'",
                                                    thread.getName(), hostName, node.hostName),
                                            ise));
                }
            }
        }
    }

    /** Must be {@code public} — used as method reference from advice inlined into {@code java.lang.ThreadBuilders}. */
    public void scheduleNow(Runnable runnable) {
        context.scheduler()
                .scheduleExactlyAt(
                        this, runnable, instant().plusNanos(defaultSchedulingJitter.applyAsLong(context.random())));
    }

    /** Must be {@code public} — called from advice inlined into {@code java.lang.VirtualThread}. */
    public Future<?> scheduleAfterDelay(Runnable runnable, long delay, TimeUnit unit) {
        return context.scheduler().scheduleExactlyAt(this, runnable, instant().plus(delay, unit.toChronoUnit()));
    }

    /** Deterministic implementation of {@code java.util.ImmutableCollections#REVERSE}. */
    final boolean immutableCollectionsReverse() {
        return reverse;
    }

    /** Deterministic implementation of {@code java.util.ImmutableCollections#SALT32L}. */
    final long immutableCollectionsSalt32l() {
        return salt32l;
    }

    /**
     * {@return the current simulated wall-clock time in nanoseconds.}
     *
     * <p>Must be {@code public} — called from advice inlined into {@code java.lang.System}.
     */
    public long nanoTime() {
        return NANOS.between(Simulator.START_TIME, instant());
    }

    /**
     * {@return the current simulated wall-clock time in milliseconds.}
     *
     * <p>Must be {@code public} — called from advice inlined into {@code java.lang.System}.
     */
    public long currentTimeMillis() {
        return instant().toEpochMilli();
    }

    /** Must be {@code public} — called from advice inlined into {@code java.lang.Runtime}. */
    public void addShutdownHook(Thread hook) {
        shutdownHooks.add(hook);
    }

    /** Must be {@code public} — called from advice inlined into {@code java.lang.Runtime}. */
    public boolean removeShutdownHook(Thread hook) {
        return shutdownHooks.remove(hook);
    }

    /** Must be {@code public} — called from advice inlined into {@code java.lang.Runtime}. */
    public void exit(int status) {
        throw new Simulator.SystemExitError(status);
    }

    void purgeAndUnblockVirtualThreads() {
        virtualThreads.forEach(this::unblockWaitingThread);
    }

    private void unblockWaitingThread(Thread thread) {
        requireNonNull(thread);
        if (thread.isAlive() && thread.getState() != TERMINATED) {
            if (isOnWaitingList(thread)) {
                while (getNext(thread) != null) {
                    onSpinWait();
                }
                if (!compareAndSetOnWaitingList(thread, true, false)) {
                    throw new Simulator.SimulationError(format(
                            "Thread '%s' is no more present on the waiting-list. Determinism is broken",
                            thread.getName()));
                }
                Threads.Internals.unblock(thread);
            }
        }
    }

    private void uncaughtExceptionHandler(Thread thread, Throwable throwable) {
        context.simulator().uncaughtExceptionHandler(this, thread, throwable);
    }

    @SuppressWarnings({"deprecation", "removal"})
    SocketImpl route(InetAddress from, InetAddress address, int port)
            throws UnknownHostException, NoRouteToHostException {
        return netInterfaces.isLocal(address)
                ? netInterfaces.route(address, port)
                : context.network().route(from, address, port);
    }

    private class NodeInterfaces {
        private static final int EPHEMERAL_RANGE_START = 32768;
        private static final int EPHEMERAL_RANGE_END = 61000;
        private final Set<String> addresses;
        private final Map<String, SocketImpl> boundSockets = new HashMap<>();

        NodeInterfaces(InetAddress localHost) {
            addresses = localHost.isLoopbackAddress()
                    ? Set.of(localHost.getHostAddress())
                    : Set.of(getLoopbackAddress().getHostAddress(), localHost.getHostAddress());
        }

        boolean isLocal(InetAddress address) {
            return addresses.contains(address.getHostAddress());
        }

        SocketImpl route(InetAddress address, int port) throws UnknownHostException, NoRouteToHostException {
            if (!isLocal(address)) {
                throw new UnknownHostException(address.getHostName());
            }
            var hostPort = toHostPort(address, port);
            var socket = boundSockets.get(hostPort);
            if (socket == null) {
                throw new NoRouteToHostException(address.getHostName());
            }
            return socket;
        }

        Binding bind(InetAddress address, int port, SocketImpl socket, boolean reuseAddress)
                throws BindException {
            for (int i = EPHEMERAL_RANGE_START; port == 0 && i < EPHEMERAL_RANGE_END; i++) {
                if (!boundSockets.containsKey(toHostPort(address, i))) {
                    port = i;
                }
            }
            if (address.isAnyLocalAddress()) {
                // Check that the port is available on all addresses
                var anyHostPort = new ArrayList<String>(addresses.size());
                for (var addr : addresses) {
                    var hostPort = toHostPort(addr, port);
                    checkNotInUse(hostPort, reuseAddress);
                    anyHostPort.add(hostPort);
                }
                anyHostPort.forEach(hostPort -> boundSockets.put(hostPort, socket));
                return new Binding(
                        address, port, () -> anyHostPort.forEach(hp -> boundSockets.remove(hp, socket)));
            } else if (isLocal(address)) {
                var hostPort = toHostPort(address, port);
                checkNotInUse(hostPort, reuseAddress);
                boundSockets.put(hostPort, socket);
                return new Binding(address, port, () -> boundSockets.remove(hostPort));
            } else {
                throw new BindException("Cannot assign requested address");
            }
        }

        private void checkNotInUse(String hostPort, boolean reuseAddress) throws BindException {
            context.faultInjector().onNetworkBind(reuseAddress);
            if (boundSockets.containsKey(hostPort)) {
                throw new BindException("Address already in use");
            }
        }

        private static String toHostPort(InetAddress address, int port) {
            return toHostPort(address.getHostAddress(), port);
        }

        private static String toHostPort(String address, int port) {
            return format("%s:%s", address, port);
        }
    }
}
