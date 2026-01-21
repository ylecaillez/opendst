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
import static com.pingidentity.opendst.Threads.Internals.unblock;
import static java.lang.Byte.toUnsignedInt;
import static java.lang.String.format;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.ofVirtual;
import static java.lang.Thread.onSpinWait;
import static java.net.InetAddress.getByName;
import static java.net.InetAddress.getLoopbackAddress;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

/**
 * Represents an isolated execution environment (a node) within the simulation.
 * <p>
 * A Node provides its own IP address, class loader, and deterministic implementations of JDK APIs.
 */
public final class Node {
    static final ThreadLocal<Node> CURRENT = new ThreadLocal<>();
    private static final Path MACHINE_FS_DIR = Path.of("fs");

    public final SimulationContext context;
    public final String hostName;
    final Path workingDirectory;
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
    private final NodeSocketFactory socketFactory = new NodeSocketFactory();
    private final NodeServerSocketFactory serverSocketFactory = new NodeServerSocketFactory();

    private static final int MAX_VIRTUAL_THREADS = 1000;
    private static final int MAX_BOUND_SOCKETS = 1000;
    private static final int MAX_BACKLOG = 1024;
    private static final int MAX_BUFFER_CAPACITY = 1024 * 1024; // 1MB

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
        this.workingDirectory = MACHINE_FS_DIR.resolve(hostName).toAbsolutePath();
        Files.createDirectories(workingDirectory);
        this.salt32l = context.random().nextLong() & 0xFFFF_FFFFL;
        this.reverse = (salt32l & 1) == 0;
        this.console = new PrintStream(context.logger().newLogWriter(hostName), true);

        this.localHost = InetAddress.ofLiteral(localIpAddress);
        this.netInterfaces = new NodeInterfaces(localHost);
        context.network().registerDns(hostName, this);
    }

    /** {@return The Node attached to this thread or null if this thread is not part of a simulation}. */
    public static Node currentNodeOrNull() {
        var node = CURRENT.get();
        if (node == null) {
            CURRENT.remove();
        }
        return node;
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

    void flush() {
        console.flush();
    }

    public InetAddress getLocalHost() {
        return localHost;
    }

    public Instant instant() {
        return context.scheduler().now();
    }

    public Randomness.Source random() {
        return context.random();
    }

    public ConsoleCapture logger() {
        return context.logger();
    }

    public Lock lock() {
        return context.lock();
    }

    public Faults.Injector faultInjector() {
        return context.faultInjector();
    }

    public Network network() {
        return context.network();
    }

    public Time.Scheduler scheduler() {
        return context.scheduler();
    }

    public String lookupByAddress(InetAddress addr) throws UnknownHostException {
        if (addr.isLoopbackAddress()) {
            return "localhost";
        } else if (netInterfaces.isLocal(addr)) {
            return hostName;
        } else {
            return context.network().lookupByAddress(addr);
        }
    }

    public Stream<InetAddress> lookupByName(String hostName) throws UnknownHostException {
        var hostname = hostName.toLowerCase(ROOT);
        if ("localhost".equals(hostname)) {
            return Stream.of(getLoopbackAddress());
        } else if (this.hostName.equals(hostname)) {
            return Stream.of(localHost);
        } else {
            return context.network().lookupByName(hostName);
        }
    }

    Stream<InetAddress> inetAddresses() {
        return Stream.of(localHost);
    }

    public SocketFactory socketFactory() {
        return socketFactory;
    }

    public ServerSocketFactory serverSocketFactory() {
        return serverSocketFactory;
    }

    @SuppressWarnings({"deprecation", "removal"})
    public SocketImpl newSocketImpl(boolean isServer) {
        return new NodeSocketImpl(isServer);
    }

    /**
     * Attaches the provided thread to this node.
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
        setThreadLocal(thread, CURRENT, this);
        thread.setUncaughtExceptionHandler(this::uncaughtExceptionHandler);
        thread.setContextClassLoader(classLoader);
    }

    /**
     * Executes the provided scenario on this node.
     *
     * @param scenario The scenario to execute
     */
    public void startNode(Callable<Void> scenario) {
        requireNonNull(scenario);
        ofVirtual().name(hostName + "-main").start(() -> {
            CURRENT.set(this);
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
    }

    public void block(Thread thread) {
        requireNonNull(thread);
        if (thread != currentThread()) {
            throw new Simulator.SimulationError("A thread can only block itself");
        }

        context.lock().unlock();
        try {
            while (!isOnWaitingList(thread)) {
                onSpinWait();
            }
        } finally {
            context.lock().lock();
        }
    }

    public void unblock(Thread thread) {
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
                unblock(thread);
            }
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

    public void scheduleNow(Runnable runnable) {
        context.scheduler()
                .scheduleExactlyAt(
                        this, runnable, instant().plusNanos(defaultSchedulingJitter.applyAsLong(context.random())));
    }

    public Future<?> scheduleAfterDelay(Runnable runnable, long delay, TimeUnit unit) {
        return context.scheduler().scheduleExactlyAt(this, runnable, instant().plus(delay, unit.toChronoUnit()));
    }

    /** Deterministic implementation of {@code java.util.ImmutableCollections#REVERSE}. */
    public final boolean immutableCollectionsReverse() {
        return reverse;
    }

    /** Deterministic implementation of {@code java.util.ImmutableCollections#SALT32L}. */
    public final long immutableCollectionsSalt32l() {
        return salt32l;
    }

    /** {@return the current simulated wall-clock time in nanoseconds.} */
    public long nanoTime() {
        return NANOS.between(Simulator.START_TIME, instant());
    }

    /** {@return the current simulated wall-clock time in milliseconds.} */
    public long currentTimeMillis() {
        return instant().toEpochMilli();
    }

    public void addShutdownHook(Thread hook) {
        shutdownHooks.add(hook);
    }

    public boolean removeShutdownHook(Thread hook) {
        return shutdownHooks.remove(hook);
    }

    public void exit(int status) {
        throw new Simulator.SystemExitError(status);
    }

    public void purgeAndUnblockVirtualThreads() {
        for (var thread : virtualThreads) {
            unblock(thread);
        }
    }

    public void uncaughtExceptionHandler(Thread thread, Throwable throwable) {
        context.simulator().uncaughtExceptionHandler(this, thread, throwable);
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    private void checkIsLocalAddress(InetAddress address) throws IOException {
        if (!netInterfaces.isLocal(address)) {
            throw new SocketException("Cannot assign requested address");
        }
    }

    private final class NodeSocketFactory extends SocketFactory {
        @Override
        public Socket createSocket() throws IOException {
            return new NetSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return createSocket(host, port, InetAddress.getLocalHost(), 0);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return createSocket(getByName(host), port, localAddress, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return createSocket(host, port, InetAddress.getLocalHost(), 0);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            checkIsLocalAddress(localAddress);
            var socket = new NetSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }

        @SuppressWarnings({"deprecation", "removal"})
        private final class NetSocket extends Socket {
            private NetSocket() throws SocketException {
                super(new NodeSocketImpl(false));
            }
        }
    }

    private final class NodeServerSocketFactory extends ServerSocketFactory {
        @Override
        public ServerSocket createServerSocket() {
            return new NetServerSocket();
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return createServerSocket(port, 128);
        }

        @Override
        public ServerSocket createServerSocket(int port, int backlog) throws IOException {
            var serverSocket = new NetServerSocket();
            serverSocket.bind(new InetSocketAddress((InetAddress) null, port), backlog);
            return serverSocket;
        }

        @Override
        public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
            checkIsLocalAddress(ifAddress);
            var serverSocket = new NetServerSocket();
            serverSocket.bind(new InetSocketAddress(ifAddress, port));
            return serverSocket;
        }

        @SuppressWarnings({"deprecation", "removal"})
        private final class NetServerSocket extends ServerSocket {
            NetServerSocket() {
                super(new NodeSocketImpl(true));
            }

            @Override
            public Socket accept() throws IOException {
                if (isClosed()) {
                    throw new SocketException("Socket is closed");
                } else if (!isBound()) {
                    throw new SocketException("Socket is not bound yet");
                }
                var s = socketFactory.createSocket();
                implAccept(s);
                return s;
            }
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    public final class NodeSocketImpl extends SocketImpl implements Closeable {
        private final class NodeBuffer {
            private final Condition notFull = context.lock().newCondition();
            private final Condition notEmpty = context.lock().newCondition();
            private int capacity;
            private byte[] buffer;
            private int writePos = 0;
            private int readPos = 0;

            NodeBuffer(int capacity) {
                assert capacity > 0;
                this.capacity = Math.min(capacity, MAX_BUFFER_CAPACITY) + 1;
                this.buffer = new byte[Math.min(this.capacity, 32)];
            }

            void trySetCapacity(int newCapacity) {
                assert newCapacity > 0;
                newCapacity = Math.min(newCapacity, MAX_BUFFER_CAPACITY);
                if (writePos < newCapacity && readPos < newCapacity) {
                    capacity = newCapacity + 1;
                    if (buffer.length > capacity) {
                        buffer = Arrays.copyOf(buffer, capacity);
                    }
                }
            }

            int capacity() {
                return capacity - 1;
            }

            int writeableBytes() {
                return (capacity - writePos + readPos - 1) % capacity;
            }

            void write(byte[] src, int off, int len) {
                writeActual(src, off, len);
            }

            void writeDelayed(byte[] src, int off, int len, long delayMs) {
                if (delayMs == 0) {
                    writeActual(src, off, len);
                    return;
                }

                byte[] data = new byte[len];
                System.arraycopy(src, off, data, 0, len);

                context.scheduler()
                        .scheduleAfterDelay(
                                Node.this,
                                () -> {
                                    context.lock().lock();
                                    try {
                                        writeActual(data, 0, len);
                                    } finally {
                                        context.lock().unlock();
                                    }
                                },
                                delayMs,
                                java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            private void writeActual(byte[] src, int off, int len) {
                tryGrowBuffer(len);
                if (len + writePos <= buffer.length) {
                    System.arraycopy(src, off, buffer, writePos, len);
                } else {
                    int bytesToEnd = buffer.length - writePos;
                    System.arraycopy(src, off, buffer, writePos, bytesToEnd);
                    int bytesAtStart = len - bytesToEnd;
                    System.arraycopy(src, off + bytesToEnd, buffer, 0, bytesAtStart);
                }
                writePos = (writePos + len) % capacity;
                notEmpty.signalAll();
            }

            void tryGrowBuffer(int bytesToWrite) {
                int newLength = Math.min(capacity, buffer.length + bytesToWrite + 32);
                buffer = newLength == buffer.length ? buffer : Arrays.copyOf(buffer, newLength);
            }

            void waitIfFull() throws IOException {
                try {
                    notFull.await();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }

            void signalCanWrite() {
                notFull.signalAll();
            }

            int readableBytes() {
                return (capacity - readPos + writePos) % capacity;
            }

            int read(byte[] dest, int off, int len) {
                if (readPos + len <= buffer.length) {
                    System.arraycopy(buffer, readPos, dest, off, len);
                } else {
                    int bytesToEnd = buffer.length - readPos;
                    System.arraycopy(buffer, readPos, dest, off, bytesToEnd);
                    int bytesAtStart = len - bytesToEnd;
                    System.arraycopy(buffer, 0, dest, off + bytesToEnd, bytesAtStart);
                }
                readPos = (readPos + len) % capacity;
                notFull.signalAll();
                return len;
            }

            boolean waitIfEmpty(long maxDurationMs) {
                try {
                    if (maxDurationMs == 0) {
                        notEmpty.await();
                        return true;
                    } else {
                        return notEmpty.await(maxDurationMs, MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    return false;
                }
            }

            void signalCanRead() {
                notEmpty.signalAll();
            }
        }

        private SynchronousQueue<NodeSocketImpl> connected;
        private NodeBuffer receiveBuffer;
        private boolean isServer;
        private ArrayBlockingQueue<NodeSocketImpl> backlog;

        private final String hostName;
        private Binding binding;
        private NodeSocketImpl peer;
        private boolean soReuseAddr;
        private boolean isOutputShutdown;
        private boolean isInputShutdown;
        private int soTimeout;

        private Object soLinger;
        private boolean closed;

        public NodeSocketImpl(boolean isServer) {
            this.isServer = isServer;
            this.hostName = Node.this.hostName;
            if (isServer) {
                this.backlog = new ArrayBlockingQueue<>(128);
            } else {
                this.receiveBuffer = new NodeBuffer(4096);
                this.connected = new SynchronousQueue<>();
            }
        }

        @Override
        public void create(boolean stream) throws IOException {
            if (!stream) {
                throw new IOException("Datagram sockets are not supported");
            }
            if (receiveBuffer == null && !isServer) {
                receiveBuffer = new NodeBuffer(4096);
                connected = new SynchronousQueue<>();
            }
        }

        @Override
        public void connect(String host, int port) throws IOException {
            connect(getByName(host), port);
        }

        @Override
        public void connect(InetAddress address, int port) throws IOException {
            connect(new InetSocketAddress(address, port), 10_000);
        }

        @Override
        public void connect(SocketAddress toAddress, int timeoutMillis) throws IOException {
            if (!(toAddress instanceof InetSocketAddress peerSocketAddress)) {
                throw new IOException("Unsupported toAddress type");
            } else if (peerSocketAddress.isUnresolved()) {
                throw new UnknownHostException(peerSocketAddress.getHostName());
            }
            if (receiveBuffer == null) {
                receiveBuffer = new NodeBuffer(4096);
                connected = new SynchronousQueue<>();
            }
            try {
                var peerAddress = peerSocketAddress.getAddress();

                if (binding == null) {
                    binding = netInterfaces.bind(
                            peerAddress.isLoopbackAddress() ? getLoopbackAddress() : localHost,
                            localport,
                            this,
                            soReuseAddr);
                    localport = binding.port();
                }

                int peerPort = peerSocketAddress.getPort();
                var peerSocket = route(binding.address(), peerAddress, peerPort);
                if (!(peerSocket instanceof NodeSocketImpl listeningSocket) || !listeningSocket.isServer) {
                    throw new SocketException("Connection refused");
                }
                address = peerAddress;
                port = peerPort;
                if (!listeningSocket.backlog.offer(this)) {
                    throw new SocketException("Connection refused");
                } else if (timeoutMillis > 0 && (peer = connected.poll(timeoutMillis, MILLISECONDS)) == null) {
                    throw new SocketTimeoutException();
                } else if (timeoutMillis == 0) {
                    peer = connected.take();
                }
            } catch (InterruptedException e) {
                close();
                throw new SocketException("Closed by interrupt");
            } catch (SocketException e) {
                close();
                throw e;
            }
        }

        @Override
        public void bind(InetAddress host, int port) throws IOException {
            binding = netInterfaces.bind(host, port, this, soReuseAddr);
            localport = binding.port();
            if (isServer) {
                address = binding.address();
            }
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    var data = new byte[1];
                    int bytesRead = read(data);
                    return bytesRead == 1 ? toUnsignedInt(data[0]) : -1;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    Objects.requireNonNull(b);
                    Objects.checkFromIndexSize(off, len, b.length);
                    if (receiveBuffer == null) {
                        throw new SocketException("Not connected");
                    }
                    context.lock().lock();
                    try {
                        for (; ; ) {
                            if (closed) {
                                throw new SocketException("Socket is closed");
                            } else if (isInputShutdown) {
                                return -1;
                            }
                            int readable = Math.min(len, receiveBuffer.readableBytes());
                            if (readable > 0) {
                                int read = context.random().nextBoolean()
                                        ? readable
                                        : context.random().nextInt(1, readable + 1);
                                return receiveBuffer.read(b, off, read);
                            } else if (peer != null && peer.isOutputShutdown) {
                                return -1;
                            } else if (!receiveBuffer.waitIfEmpty(soTimeout)) {
                                throw new SocketTimeoutException();
                            }
                        }
                    } finally {
                        context.lock().unlock();
                    }
                }

                @Override
                public int available() {
                    if (receiveBuffer == null) return 0;
                    context.lock().lock();
                    try {
                        return isInputShutdown ? 0 : receiveBuffer.readableBytes();
                    } finally {
                        context.lock().unlock();
                    }
                }

                @Override
                public void close() {
                    shutdownInput();
                }
            };
        }

        @Override
        public void shutdownInput() {
            context.lock().lock();
            try {
                isInputShutdown = true;
                if (receiveBuffer != null) {
                    receiveBuffer.signalCanRead();
                }
            } finally {
                context.lock().unlock();
            }
        }

        @Override
        public void shutdownOutput() {
            context.lock().lock();
            try {
                isOutputShutdown = true;
                if (peer != null && peer.receiveBuffer != null) {
                    peer.receiveBuffer.signalCanWrite();
                    peer.receiveBuffer.signalCanRead();
                }
            } finally {
                context.lock().unlock();
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b});
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    Objects.requireNonNull(b);
                    Objects.checkFromIndexSize(off, len, b.length);
                    if (peer == null || peer.receiveBuffer == null) {
                        throw new SocketException("Not connected");
                    }
                    context.lock().lock();
                    try {
                        for (int bytesWritten, totalWritten = 0; totalWritten < len; totalWritten += bytesWritten) {
                            if (closed) {
                                throw new SocketException("socket is closed");
                            } else if (peer.closed) {
                                throw new SocketException("Connection reset");
                            } else if (isOutputShutdown) {
                                throw new SocketException("Socket output is shutdown");
                            } else if (peer.isInputShutdown) {
                                return;
                            }

                            long latencyMs = context.faultInjector().onNetworkWrite(hostName, peer.hostName);

                            bytesWritten = Math.min(len - totalWritten, peer.receiveBuffer.writeableBytes());
                            if (bytesWritten > 0) {
                                peer.receiveBuffer.writeDelayed(
                                        b,
                                        off + totalWritten,
                                        bytesWritten,
                                        context.network().currentLatencyMs() + latencyMs);
                            } else {
                                peer.receiveBuffer.waitIfFull();
                            }
                        }
                    } finally {
                        context.lock().unlock();
                    }
                    simulateBandwidth();
                }

                private void simulateBandwidth() {
                    long bandwidthMs = context.random().nextInt(0, 3);
                    try {
                        Thread.sleep(bandwidthMs);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                @Override
                public void close() {
                    NodeSocketImpl.this.close();
                }
            };
        }

        @Override
        public int available() throws IOException {
            if (receiveBuffer == null) return 0;
            context.lock().lock();
            try {
                if (closed) {
                    throw new SocketException("socket is closed");
                }
                return isInputShutdown ? -1 : receiveBuffer.readableBytes();
            } finally {
                context.lock().unlock();
            }
        }

        @Override
        public void close() {
            context.lock().lock();
            try {
                closed = isOutputShutdown = isInputShutdown = true;
                if (binding != null) {
                    binding.close();
                }
                if (isServer) {
                    if (backlog != null) {
                        backlog.offer(new NodeSocketImpl(false));
                    }
                } else {
                    if (receiveBuffer != null) {
                        receiveBuffer.signalCanRead();
                    }
                    if (peer != null && peer.receiveBuffer != null) {
                        peer.receiveBuffer.signalCanWrite();
                        peer.receiveBuffer.signalCanRead();
                    }
                }
            } finally {
                context.lock().unlock();
            }
        }

        @Override
        public void sendUrgentData(int data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void setOption(SocketOption<T> name, T value) {
            if (StandardSocketOptions.SO_RCVBUF.equals(name)) {
                setOption(SO_RCVBUF, value);
            } else if (StandardSocketOptions.SO_REUSEADDR.equals(name)) {
                setOption(SO_REUSEADDR, value);
            } else if (StandardSocketOptions.SO_LINGER.equals(name)) {
                setOption(SO_LINGER, value);
            } else if (StandardSocketOptions.TCP_NODELAY.equals(name)) {
                setOption(TCP_NODELAY, value);
            } else if (StandardSocketOptions.SO_KEEPALIVE.equals(name)) {
                setOption(SO_KEEPALIVE, value);
            } else {
                throw new UnsupportedOperationException(name.name());
            }
        }

        @Override
        public void setOption(int optID, Object value) {
            if (optID == SO_TIMEOUT) {
                soTimeout = (Integer) value;
            } else if (optID == SO_REUSEADDR) {
                soReuseAddr = (Boolean) value;
            } else if (optID == SO_LINGER) {
                soLinger = value;
            } else if (optID == SO_RCVBUF) {
                if (receiveBuffer != null) {
                    receiveBuffer.trySetCapacity((Integer) value);
                }
            } else if (optID == TCP_NODELAY || optID == SO_KEEPALIVE) {
                // Ignored
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public Object getOption(int optID) {
            if (optID == SO_TIMEOUT) {
                return soTimeout;
            } else if (optID == SO_REUSEADDR) {
                return soReuseAddr;
            } else if (optID == SO_RCVBUF) {
                return receiveBuffer != null ? receiveBuffer.capacity() : 4096;
            } else if (optID == SO_LINGER) {
                return soLinger;
            } else if (optID == SO_BINDADDR) {
                return binding != null ? binding.address() : InetAddress.ofLiteral("0.0.0.0");
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public void listen(int backlog) {
            assert backlog > 0;
            this.isServer = true;
            this.backlog = new ArrayBlockingQueue<>(Math.min(backlog, MAX_BACKLOG));
        }

        @Override
        public void accept(SocketImpl socket) throws IOException {
            if (!(socket instanceof NodeSocketImpl acceptedLocalSocket)) {
                context.simulator()
                        .exitSimulation(
                                INTERNAL_ERROR,
                                new Simulator.SimulationError("The accepted local socket is not a NodeSocketImpl: '%s'"
                                        .formatted(socket.toString())));
                throw new IOException("Not a NodeSocketImpl");
            }
            try {
                var peerSocket = soTimeout == 0 ? backlog.take() : backlog.poll(soTimeout, MILLISECONDS);
                if (closed) {
                    throw new SocketException("Socket closed");
                } else if (peerSocket == null) {
                    throw new InterruptedIOException();
                }
                acceptedLocalSocket.binding = new Binding(peerSocket.address, peerSocket.port, () -> {});
                acceptedLocalSocket.localport = localport;
                acceptedLocalSocket.address = peerSocket.binding.address();
                acceptedLocalSocket.port = peerSocket.binding.port();
                acceptedLocalSocket.peer = peerSocket;
                peerSocket.connected.offer(acceptedLocalSocket);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getOption(SocketOption<T> name) {
            if (StandardSocketOptions.SO_RCVBUF.equals(name)) {
                return (T) getOption(SO_RCVBUF);
            } else if (StandardSocketOptions.SO_REUSEADDR.equals(name)) {
                return (T) getOption(SO_REUSEADDR);
            } else if (StandardSocketOptions.SO_LINGER.equals(name)) {
                return (T) getOption(SO_LINGER);
            } else {
                throw new UnsupportedOperationException(name.name());
            }
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return Set.of(
                    StandardSocketOptions.SO_RCVBUF,
                    StandardSocketOptions.SO_REUSEADDR,
                    StandardSocketOptions.SO_LINGER,
                    StandardSocketOptions.TCP_NODELAY,
                    StandardSocketOptions.SO_KEEPALIVE);
        }

        @Override
        public FileDescriptor getFileDescriptor() {
            return null;
        }

        @Override
        public InetAddress getInetAddress() {
            return address;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public int getLocalPort() {
            return localport;
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    SocketImpl route(InetAddress from, InetAddress address, int port)
            throws UnknownHostException, NoRouteToHostException {
        return netInterfaces.isLocal(address)
                ? netInterfaces.route(address, port)
                : context.network().route(from, address, port);
    }

    private record Binding(InetAddress address, int port, Closeable closeable) implements Closeable {
        @Override
        public void close() {
            try {
                closeable.close();
            } catch (IOException e) {
                // Ignore silently
            }
        }
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
            assert address != null;
            assert socket != null;

            if (boundSockets.size() >= MAX_BOUND_SOCKETS) {
                throw new BindException("Maximum number of bound sockets reached");
            }

            if (port == 0) {
                port = context.random().nextInt(EPHEMERAL_RANGE_START, EPHEMERAL_RANGE_END);
            }
            if (address.isAnyLocalAddress()) {
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

        private static String toHostPort(InetAddress address, int port) {
            return toHostPort(address.getHostAddress(), port);
        }

        private static String toHostPort(String address, int port) {
            return format("%s:%s", address, port);
        }
    }
}
