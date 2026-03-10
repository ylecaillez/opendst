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
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.lang.System.arraycopy;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.ofVirtual;
import static java.lang.Thread.onSpinWait;
import static java.lang.Thread.sleep;
import static java.lang.Thread.startVirtualThread;
import static java.net.InetAddress.getByName;
import static java.net.InetAddress.getLoopbackAddress;
import static java.time.Duration.ofNanos;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.Objects.checkFromIndexSize;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.ThreadLocalRandom.current;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
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

    public Faults.Injector faultInjector() {
        return context.faultInjector();
    }

    public Network network() {
        return context.network();
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


    @SuppressWarnings("serial")
    private static final class AsyncVar extends ReentrantLock {
        private final Condition condition = newCondition();
        private long changeCount;
        private long value;

        void await() throws InterruptedIOException {
            lock();
            try {
                long change = changeCount;
                while (change == changeCount) {
                    condition.await();
                }
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            } finally {
                unlock();
            }
        }

        boolean await(long nanos) throws InterruptedIOException {
            if (nanos == 0) {
                await();
                return true;
            }
            lock();
            try {
                long change = changeCount;
                while (nanos > 0 && change == changeCount) {
                    nanos = condition.awaitNanos(nanos);
                }
                return change != changeCount;
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            } finally {
                unlock();
            }
        }

        void add(long value) {
            set(this.value + value);
        }

        void set(long value) {
            if (value != this.value) {
                this.value = value;
                signal();
            }
        }

        void signal() {
            lock();
            try {
                changeCount++;
                condition.signalAll();
            } finally {
                unlock();
            }
        }

        long get() {
            return value;
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    public final class NodeSocketImpl extends SocketImpl implements Closeable {
        private static final class NetBuffer {
            private static final int CHUNK_SIZE = 4096;
            private final ArrayDeque<byte[]> buffer = new ArrayDeque<>();
            private int writePos = CHUNK_SIZE;
            private int readPos;

            void append(byte[] b, int offset, int len) {
                for (int written; len > 0; len -= written, offset += written, writePos += written) {
                    addBufferIfFull();
                    written = min(CHUNK_SIZE - writePos, len);
                    arraycopy(b, offset, buffer.getLast(), writePos, written);
                }
            }

            private void addBufferIfFull() {
                if (writePos == CHUNK_SIZE) {
                    buffer.add(new byte[CHUNK_SIZE]);
                    writePos = 0;
                }
            }

            private int size() {
                return buffer.size() * CHUNK_SIZE - readPos;
            }

            void read(byte[] b, int offset, int len) {
                for (int read; len > 0 && !buffer.isEmpty(); len -= read, offset += read, readPos += read) {
                    read = min(len, buffer.size() == 1 ? writePos - readPos : CHUNK_SIZE - readPos);
                    arraycopy(buffer.getFirst(), readPos, b, offset, read);
                    removeBufferIfEmpty();
                }
            }

            private void removeBufferIfEmpty() {
                if (readPos == CHUNK_SIZE) {
                    buffer.removeFirst();
                    readPos = 0;
                }
            }
        }

        private SynchronousQueue<NodeSocketImpl> connected;
        private NetBuffer receiveBuffer;
        /** Bytes already pulled from buffer (location of the beginning of recvBuf) */
        private final AsyncVar readBytes = new AsyncVar();

        private final AsyncVar receivedBytes = new AsyncVar();
        private final AsyncVar sentBytes = new AsyncVar();
        private final AsyncVar writtenBytes = new AsyncVar();
        private boolean tcpFINSent;
        private boolean tcpFINReceived;
        private int sendBufferSize;
        private boolean isServer;
        private ArrayBlockingQueue<NodeSocketImpl> backlog;

        private Binding binding;
        private boolean stableConnection;
        private NodeSocketImpl peer;
        private boolean soReuseAddr;
        private boolean isOutputShutdown;
        private boolean isInputShutdown;
        private int soTimeout;

        private Object soLinger;
        private boolean closed;

        public NodeSocketImpl(boolean isServer) {
            this.isServer = isServer;
            this.connected = isServer ? null : new SynchronousQueue<>();
            this.backlog = isServer ? new ArrayBlockingQueue<>(128) : null;
        }

        private Node node() {
            return Node.this;
        }

        @Override
        public void create(boolean stream) throws IOException {
            if (!stream) {
                throw new IOException("Datagram sockets are not supported");
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
                stableConnection = listeningSocket.node() == Node.this;
                address = peerAddress;
                port = peerPort;
                if (!listeningSocket.backlog.offer(this)) {
                    throw new SocketException("Connection refused");
                } else if (timeoutMillis > 0 && (peer = connected.poll(timeoutMillis, MILLISECONDS)) == null) {
                    throw new SocketTimeoutException();
                } else if (timeoutMillis == 0) {
                    peer = connected.take();
                }
                var latency = context.faultInjector().setPairLatencyIfNotSet(peerAddress, address,
                                                                             ofNanos((context.faults().network()
                                                                                             .cloggingLatencyMaximum()
                                                                                             .toNanos()
                                                                                     * current().nextInt(1000))
                                                                                             / 1000));
                this.sendBufferSize = toIntExact(max(current().nextLong(0, 5_000_000), 25_000 * (2 + latency.toMillis())));
                this.receiveBuffer = new NetBuffer();
                startVirtualThread(new FutureTask<>(this::receiver)).setName(address + " - receiver");
                startVirtualThread(new FutureTask<>(this::sender)).setName(address + " - sender");
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
                    byte[] a = new byte[1];
                    int n = read(a, 0, 1);
                    return (n > 0) ? (a[0] & 0xff) : -1;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    requireNonNull(b);
                    checkFromIndexSize(off, len, b.length);
                    for (; ; ) {
                        int readable = min(len, Math.toIntExact(receivedBytes.get() - readBytes.get()));
                        assert readable >= 0 && readable <= receiveBuffer.size() && readable <= len;
                        if (closed) {
                            throw new SocketException("Socket is closed");
                        } else if (isInputShutdown) {
                            return -1;
                        } else if (readable > 0) {
                            receiveBuffer.read(b, off, readable);
                            readBytes.add(readable);
                            return readable;
                        } else if (len == 0) {
                            return 0;
                        } else if (tcpFINReceived) {
                            return -1;
                        } else if (!receivedBytes.await(MILLISECONDS.toNanos(soTimeout))) {
                            throw new SocketTimeoutException();
                        }
                    }
                }

                @Override
                public int available() {
                    return isInputShutdown ? 0 : (int) (receivedBytes.get() - readBytes.get());
                }

                @Override
                public void close() {
                    shutdownInput();
                }
            };
        }

        @Override
        protected void shutdownInput() {
            isInputShutdown = true;
            sentBytes.signal();
        }

        @Override
        protected void shutdownOutput() {
            isOutputShutdown = true;
            // Unblock local write() and peer read() so that it can returns -1 if all bytes have been read
            peer.writtenBytes.signal();
        }

        private int availableSendBufferForPeer() {
            return sendBufferSize - toIntExact(writtenBytes.get() - receivedBytes.get());
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
                    requireNonNull(b);
                    checkFromIndexSize(off, len, b.length);
                    for (int bytesWritten, totalWritten = 0; totalWritten < len; totalWritten += bytesWritten) {
                        if (closed) {
                            throw new SocketException("socket is closed");
                        } else if (peer.closed) {
                            throw new SocketException("Connection reset");
                        } else if (isOutputShutdown) {
                            throw new SocketException("Socket output is shutdown");
                        } else if (peer.isInputShutdown) {
                            // Discard silently per contract
                            return;
                        }
                        bytesWritten = min(len, peer.availableSendBufferForPeer());
                        if (bytesWritten > 0) {
                            peer.receiveBuffer.append(b, off, bytesWritten);
                            peer.writtenBytes.add(bytesWritten);
                        } else {
                            peer.receivedBytes.await();
                        }
                    }
                }

                @Override
                public void close() {
                    NodeSocketImpl.this.close();
                }
            };
        }

        private Void sender() throws InterruptedException, InterruptedIOException {
            for (; ; ) {
                if (sentBytes.get() == writtenBytes.get()) {
                    // All bytes previously written by the peer have been sent
                    if (peer.isOutputShutdown) {
                        // Peer notified that no more data will be sent
                        peer.tcpFINSent = true;
                        sentBytes.signal();
                        return null;
                    }
                    // Wait for more bytes from peer
                    writtenBytes.await();
                } else {
                    // Peer has written some bytes which needs to be sent
                    long twoNanosSec = MILLISECONDS.toNanos(2);
                    sleep(ofNanos((twoNanosSec * current().nextInt(0, 1000)) / 1000));
                    sentBytes.set(writtenBytes.get());
                }
            }
        }

        private Void receiver() throws InterruptedException, InterruptedIOException {
            for (; ; ) {
                if (isInputShutdown) {
                    return null;
                } else if (context.faultInjector().isDisconnected(address, binding.address)) {
                    // TODO: Here we behaves like black-hole, should we throw an error or close the connection
                    //  instead ?
                    return null;
                }
                if (receivedBytes.get() == sentBytes.get()) {
                    // All bytes sent by peer have been received
                    if (peer.tcpFINSent) {
                        // Peer will not send bytes anymore, wake-up read() so that it can returns -1
                        tcpFINReceived = true;
                        receivedBytes.signal();
                        return null;
                    }
                    // Wait for more bytes sent by peer
                    sentBytes.await();
                } else {
                    // Receive bytes sent by peer
                    long bytes = current().nextInt(100) < 5
                            ? sentBytes.get()
                            : current().nextLong(receivedBytes.get(), sentBytes.get() + 1);
                    var delay = context.faultInjector()
                                       .networkSendDelay(peer.address, address, stableConnection)
                                       .plus(context.faultInjector()
                                                    .networkReceiveDelay(peer.address, address, stableConnection));
                    sleep(delay);
                    receivedBytes.set(bytes);
                }
            }
        }

        @Override
        public int available() throws IOException {
            if (closed) {
                throw new SocketException("socket is closed");
            }
            return isInputShutdown ? 0 : receiveBuffer.size();
        }

        @Override
        public void close() {
            closed = isOutputShutdown = isInputShutdown = true;
            if (binding != null) {
                binding.close();
            }
            if (isServer) {
                // unblock local accept()
                backlog.offer(new NodeSocketImpl(false));
            } else {
                // Unblock local read() and peer's write()
                sentBytes.signal();
                if (peer != null) {
                    peer.writtenBytes.signal();
                }
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
                if (isServer) {
                    soReuseAddr = (Boolean) value;
                }
            } else if (!isServer && optID == SO_LINGER) {
                soLinger = value;
            } else if (!isServer && optID == SO_RCVBUF) {
                // SO_RCVBUF is only a hint: simply ignore it
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
            } else if (isServer && optID == SO_REUSEADDR) {
                return soReuseAddr;
            } else if (isServer && optID == SO_RCVBUF) {
                return sendBufferSize;
            } else if (!isServer && optID == SO_LINGER) {
                return soLinger;
            } else if (optID == SO_BINDADDR) {
                return binding != null ? binding.address() : InetAddress.ofLiteral("0.0.0.0");
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        protected void listen(int backlog) {
            this.backlog = new ArrayBlockingQueue<>(backlog);
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

                if (context.faultInjector().isDisconnected(address, peerSocket.address)) {
                    acceptedLocalSocket.close();
                } else {
                    var latency = context.faultInjector().setPairLatencyIfNotSet(peerSocket.address, address,
                                                                                 ofNanos((context.faults().network()
                                                                                                 .cloggingLatencyMaximum()
                                                                                                 .toNanos()
                                                                                         * current().nextInt(1000))
                                                                                                 / 1000));
                    acceptedLocalSocket.sendBufferSize = toIntExact(max(current().nextLong(0, 5_000_000), 25 * (
                            MILLISECONDS.toMicros(2)
                                    + NANOSECONDS.toMicros(latency.toNanos()))));
                    acceptedLocalSocket.receiveBuffer = new NetBuffer();

                    startVirtualThread(new FutureTask<>(acceptedLocalSocket::receiver))
                            .setName(acceptedLocalSocket.address + " - receiver");
                    startVirtualThread(new FutureTask<>(acceptedLocalSocket::sender))
                            .setName(peerSocket.address + " - sender");
                    peerSocket.connected.offer(acceptedLocalSocket);
                    Thread.yield();
                }
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
