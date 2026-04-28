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
package com.pingidentity.opendst.simulator;

import static com.pingidentity.opendst.intercept.ThreadsInterceptors.Internals.isOnWaitingList;
import static com.pingidentity.opendst.intercept.ThreadsInterceptors.Internals.setThreadLocal;
import static com.pingidentity.opendst.simulator.SimulationContext.MAX_VIRTUAL_THREADS_PER_NODE;
import static java.lang.String.format;
import static java.lang.System.setErr;
import static java.lang.System.setOut;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.ofVirtual;
import static java.net.InetAddress.getLoopbackAddress;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.Objects.requireNonNull;

import com.pingidentity.opendst.common.AssertType;
import com.pingidentity.opendst.intercept.NetworkInterceptors;
import com.pingidentity.opendst.intercept.ThreadsInterceptors;
import com.pingidentity.opendst.intercept.ThreadsInterceptors.VirtualThreadUnblocker;
import com.pingidentity.opendst.simulator.NodeSocketImpl.Binding;
import java.io.IOException;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketImpl;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * Represents an isolated execution environment (a node) within the simulation.
 * <p>
 * A Node provides its own IP address, class loader, and deterministic implementations of JDK APIs.
 */
public final class Node {
    private static final ThreadLocal<Node> CURRENT_NODE = new ThreadLocal<>();

    final SimulationContext context;
    final String hostName;
    final ClassLoader classLoader;
    final PrintStream console;
    final List<Thread> virtualThreads = new ArrayList<>();
    final List<Thread> shutdownHooks = new ArrayList<>();
    final long salt32l;
    final boolean reverse;
    final ToLongFunction<Random> defaultSchedulingJitter = r -> r.nextLong(1, 10_000);

    private final InetAddress localHost;
    private final NodeInterfaces netInterfaces;

    Node(SimulationContext context, ClassLoader classLoader, String hostName, String localIpAddress)
            throws IOException {
        this.context = requireNonNull(context);
        this.classLoader = requireNonNull(classLoader);
        requireNonNull(hostName);
        if (hostName.isBlank()) {
            throw new IllegalArgumentException("hostName cannot be blank");
        }
        requireNonNull(localIpAddress);

        this.hostName = hostName.toLowerCase(java.util.Locale.ROOT);
        this.salt32l = context.random().nextLong() & 0xFFFF_FFFFL;
        this.reverse = (salt32l & 1) == 0;
        this.console = new PrintStream(context.logger().newLogWriter(hostName), true);

        this.localHost = InetAddress.ofLiteral(localIpAddress);
        this.netInterfaces = new NodeInterfaces(localHost);
        context.network().registerDns(hostName, this);
    }

    /** {@return the Node attached to this thread, or {@code null} if not in a simulation} */
    public static Node currentNodeOrNull() {
        return CURRENT_NODE.get();
    }

    /**
     * {@return the Node attached to the given thread, or {@code null}}
     *
     * <p>Uses reflective access to read the thread-local value from an arbitrary thread.
     */
    public static Node nodeForThreadOrNull(Thread thread) {
        return (Node) ThreadsInterceptors.Internals.getThreadLocal(thread, CURRENT_NODE);
    }

    /**
     * {@return The Node attached to this thread}.
     * @throws IllegalStateException if this thread is not part of a simulation.
     */
    public static Node currentNodeOrThrow() {
        var node = currentNodeOrNull();
        if (node == null) {
            throw new IllegalStateException(format(
                    "This operation cannot be performed from the thread '%s' as it is not part of a simulation",
                    currentThread().getName()));
        }
        return node;
    }

    /**
     * Executes the given action with this node as the current node on the calling thread.
     * Restores the previous node when the action completes.
     *
     * @param action the action to execute with this node as current
     */
    void execute(Runnable action) {
        context.simulator().hash(context.instant(), hostName);
        var previous = CURRENT_NODE.get();
        CURRENT_NODE.set(this);
        setOut(console);
        setErr(console);
        try {
            action.run();
        } finally {
            if (previous != null) {
                CURRENT_NODE.set(previous);
            } else {
                CURRENT_NODE.remove();
            }
            // Some thread blocked on monitor might have been unblocked as a result of this execution
            virtualThreads.forEach(VirtualThreadUnblocker::unblockSimulatorBackedThread);
            console.flush();
        }
    }

    /** Must be {@code public} — called from advice inlined into {@code java.net.InetAddress}. */
    public InetAddress getLocalHost() {
        return localHost;
    }

    /** {@return the deterministic host name assigned to this node}. */
    public String hostName() {
        return hostName;
    }

    // ── SDK entry points (called from com.pingidentity.opendst.sdk.*) ─────

    /**
     * Records an assertion verdict for this node, folding the message into the deterministic
     * state hash. Called from {@code AssertImpl}.
     *
     * @param kind      assertion kind (e.g. {@code "always"}, {@code "sometimes"}); resolved via
     *                  {@link AssertType#fromString(String)}
     * @param message   the human-readable assertion label
     * @param condition the evaluated assertion result
     * @param details   optional structured details to attach, or {@code null}
     */
    public void recordAssert(String kind, String message, boolean condition, Map<String, Object> details) {
        context.logger()
                .logAssert(
                        AssertType.fromString(kind),
                        requireNonNull(message),
                        condition,
                        details,
                        hostName,
                        context.instant(),
                        context.random().iteration());
    }

    /**
     * Records a guidance signal — a distance-to-violation hint for the runner. Does not affect
     * the deterministic hash. Called from {@code AssertImpl}.
     */
    public void recordGuidance(String message, Map<String, Object> guidance) {
        context.logger()
                .logGuidance(
                        requireNonNull(message),
                        guidance,
                        hostName,
                        context.instant(),
                        context.random().iteration());
    }

    /** Signals that the workload is ready to start receiving faults. Called from {@code SignalsImpl}. */
    public void signalReady() {
        context.simulator().onReady();
    }

    public Stream<InetAddress> inetAddresses() {
        return Stream.of(localHost);
    }

    /** Must be {@code public} — called from advice in {@link NetworkInterceptors}. */
    @SuppressWarnings({"deprecation", "removal"})
    public SocketImpl newSocketImpl(boolean isServer) {
        return new NodeSocketImpl(this, isServer);
    }

    @SuppressWarnings({"deprecation", "removal"})
    Binding bindSocket(InetAddress address, int port, SocketImpl socket, boolean reuseAddress) throws BindException {
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
        } else if (virtualThreads.size() >= MAX_VIRTUAL_THREADS_PER_NODE) {
            context.simulator()
                    .reportInternalError(new Simulator.SimulationError(
                            "Max number of virtual threads reached for machine '" + hostName + "'"));
        }
        virtualThreads.add(thread);
        setThreadLocal(thread, CURRENT_NODE, this);
        var ueh = thread.getUncaughtExceptionHandler();
        if (ueh == null || ueh instanceof ThreadGroup) {
            thread.setUncaughtExceptionHandler(this::uncaughtExceptionHandler);
        }
        thread.setContextClassLoader(classLoader);
    }

    /**
     * Executes the provided scenario on this node.
     *
     * @param scenario The scenario to execute
     */
    void startNode(Callable<Void> scenario) {
        requireNonNull(scenario);
        execute(() -> ofVirtual().name(hostName + "-main").start(() -> {
            try {
                scenario.call();
            } catch (Throwable e) {
                if (!(e instanceof Simulator.SystemExitError)) {
                    uncaughtExceptionHandler(currentThread(), e);
                }
            } finally {
                // TODO: Stop handling is incomplete here: we should (silently ?) prevent scheduling new tasks after
                //  once the shutdown hooks, accepting a possible memory leak. Effectively, the node lifecycle is not
                //  clearly implemented yet.
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
        }));
    }

    void checkNoThreadOnWaitingList(Node node) {
        requireNonNull(node);
        if (node != this) {
            for (var thread : virtualThreads) {
                if (isOnWaitingList(thread)) {
                    var ise = new IllegalStateException("Stack trace");
                    ise.setStackTrace(thread.getStackTrace());
                    context.simulator()
                            .reportInternalError(new Simulator.SimulationError(
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
                        this,
                        runnable,
                        context.instant().plusNanos(defaultSchedulingJitter.applyAsLong(context.random())));
    }

    /** Must be {@code public} — called from advice inlined into {@code java.lang.VirtualThread}. */
    public Future<?> scheduleAfterDelay(Runnable runnable, long delay, TimeUnit unit) {
        return context.scheduler()
                .scheduleExactlyAt(this, runnable, context.instant().plus(delay, unit.toChronoUnit()));
    }

    /** Deterministic implementation of {@code java.util.ImmutableCollections#REVERSE}. Public — called from advice. */
    public boolean immutableCollectionsReverse() {
        return reverse;
    }

    /** Deterministic implementation of {@code java.util.ImmutableCollections#SALT32L}. Public — called from advice. */
    public long immutableCollectionsSalt32l() {
        return salt32l;
    }

    /**
     * {@return the current simulated wall-clock time in nanoseconds.}
     *
     * <p>Must be {@code public} — called from advice inlined into {@code java.lang.System}.
     */
    public long nanoTime() {
        return NANOS.between(Simulator.START_TIME, context.instant());
    }

    /**
     * {@return the current simulated wall-clock time in milliseconds.}
     *
     * <p>Must be {@code public} — called from advice inlined into {@code java.lang.System}.
     */
    public long currentTimeMillis() {
        return context.instant().toEpochMilli();
    }

    /** Must be {@code public} — called from advice inlined into {@code java.lang.Runtime}. */
    public void addShutdownHook(Thread hook) {
        if (!hook.isVirtual()) {
            // Platform thread hooks cannot run deterministically inside the simulation.
            // This is expected for JUL's LogManager$Cleaner and similar JDK housekeeping threads.
            context.logger()
                    .logPlatformThreadShutdownHookSkipped(
                            hostName,
                            hook.getClass().getName(),
                            hook.getName(),
                            context.instant(),
                            context.random().iteration());
            return;
        }
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

    private void uncaughtExceptionHandler(Thread thread, Throwable throwable) {
        context.simulator().uncaughtExceptionHandler(this, thread, throwable);
    }

    @SuppressWarnings({"deprecation", "removal"})
    public SocketImpl route(InetAddress from, InetAddress address, int port)
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

        Binding bind(InetAddress address, int port, SocketImpl socket, boolean reuseAddress) throws BindException {
            // Find a free ephemeral port when port == 0.
            // When binding to a wildcard address (0.0.0.0),
            // the port must be free on ALL local addresses,
            // not just the wildcard key (which is never
            // stored in boundSockets).
            if (port == 0) {
                port = findEphemeralPort(address);
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
                return new Binding(address, port, () -> anyHostPort.forEach(hp -> boundSockets.remove(hp, socket)));
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

        /**
         * Finds the first free ephemeral port. For
         * wildcard addresses, the port must be available
         * on ALL local interfaces.
         */
        private int findEphemeralPort(InetAddress address) throws BindException {
            var checkAddresses = address.isAnyLocalAddress() ? addresses : Set.of(address.getHostAddress());
            for (int i = EPHEMERAL_RANGE_START; i < EPHEMERAL_RANGE_END; i++) {
                boolean free = true;
                for (var a : checkAddresses) {
                    if (boundSockets.containsKey(toHostPort(a, i))) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    return i;
                }
            }
            throw new BindException("No ephemeral ports available");
        }

        private static String toHostPort(InetAddress address, int port) {
            return toHostPort(address.getHostAddress(), port);
        }

        private static String toHostPort(String address, int port) {
            return format("%s:%s", address, port);
        }
    }
}
