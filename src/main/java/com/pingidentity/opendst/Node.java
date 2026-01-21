/*
 * Copyright 2025 Ping Identity Corporation
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

import static java.lang.Byte.toUnsignedInt;
import static java.lang.String.format;
import static java.net.InetAddress.getByName;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Locale.ROOT;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.locks.Condition;
import java.util.stream.Stream;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import com.pingidentity.opendst.Simulator.Machine;

/**
 * Simulated network implementing the blocking {@link Socket} API.
 * <p>
 * Nodes are an isolated execution environment. Each node have its own IP and, possibly, its own classloader. Using
 * different classloader allow to prevent conflicts of global variables for the case where several nodes are executing
 * the same piece of code. Node can be started only once and can be stopped at any time. Stopping a node will simply
 * start the Thread previously registered with {@link Runtime#addShutdownHook(Thread)}. To restart a node, one simply
 * {@link #startNode(Callable)} again with the same bootstrap.
 */
final class Node extends Machine {
    private final InetAddress localHost;
    private final NetInterfaces netInterfaces;
    private final NetSocketFactory socketFactory = new NetSocketFactory();
    private final NetServerSocketFactory serverSocketFactory = new NetServerSocketFactory();

    Node(Simulator simulator, ClassLoader classLoader, String hostName, String localIpAddress) throws IOException {
        super(simulator, classLoader, hostName);
        this.localHost = InetAddress.ofLiteral(localIpAddress);
        this.netInterfaces = new NetInterfaces(localHost);
        simulator.registerDns(hostName, this);
    }

    @Override
    public InetAddress getLocalHost() {
        return localHost;
    }

    @Override
    public String lookupByAddress(InetAddress addr) throws UnknownHostException {
        if (addr.isLoopbackAddress()) {
            return "localhost";
        } else if (netInterfaces.isLocal(addr)) {
            return hostName;
        } else {
            return simulator.lookupByAddress(addr);
        }
    }

    @Override
    public Stream<InetAddress> lookupByName(String hostName) throws UnknownHostException {
        var hostname = hostName.toLowerCase(ROOT);
        if ("localhost".equals(hostname)) {
            return Stream.of(getLoopbackAddress());
        } else if (this.hostName.equals(hostname)) {
            return Stream.of(localHost);
        } else {
            return simulator.lookupByName(hostName);
        }
    }

    Stream<InetAddress> inetAddresses() {
        return Stream.of(localHost);
    }

    @Override
    public SocketFactory socketFactory() {
        return socketFactory;
    }

    @Override
    public ServerSocketFactory serverSocketFactory() {
        return serverSocketFactory;
    }

    private void checkIsLocalAddress(InetAddress address) throws IOException {
        if (!netInterfaces.isLocal(address)) {
            throw new SocketException("Cannot assign requested address");
        }
    }

    private final class NetSocketFactory extends SocketFactory {
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

        private final class NetSocket extends Socket {
            private NetSocket() throws SocketException {
                super(new NetSocketImpl(false));
            }
        }
    }

    private final class NetServerSocketFactory extends ServerSocketFactory {
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

        private final class NetServerSocket extends ServerSocket {
            NetServerSocket() {
                super(new NetSocketImpl(true));
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

    private final class NetSocketImpl extends SocketImpl implements Closeable {
        /** Circular buffer used in half-duplex data transmission. */
        private final class NetBuffer {
            private final Condition notFull = simulator.lock.newCondition();
            private final Condition notEmpty = simulator.lock.newCondition();
            private int capacity;
            private byte[] buffer;
            private int writePos = 0;
            private int readPos = 0;

            NetBuffer(int capacity) {
                // Problem with circular buffer happens when readPos == writePos as there is no way to know whether
                // the buffer is full or empty. A famous trick is to allocate one more slot to the buffer so that the
                // only case where readPos == writePos is when the buffer is empty.
                this.capacity = capacity + 1;
                this.buffer = new byte[Math.min(this.capacity, 32)];
            }

            void trySetCapacity(int newCapacity) {
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
                tryGrowBuffer(len);
                if (len + writePos <= buffer.length) {
                    // Single copy, no wrap-around
                    System.arraycopy(src, off, buffer, writePos, len);
                } else {
                    // Two copies, wraps around the end of the buffer
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
                    // Single copy, no wrap-around
                    System.arraycopy(buffer, readPos, dest, off, len);
                } else {
                    // Two copies, wraps around the end of the buffer
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

        /** Receives the connected {@link SocketImpl} as accepted by the server side of this connection. */
        private final SynchronousQueue<NetSocketImpl> connected;
        /** Contains the bytes sent by the peer, waiting to be read by the application. */
        private final NetBuffer receiveBuffer;
        /**
         * {@code true} if this is a server socket handling the {@link #accept(SocketImpl)} side. {@code false} if
         * it is a client socket handling the {@link #connect(String, int)} side.
         */
        private final boolean isServer;
        /** For server sockets, contains the socket waiting to be accepted. */
        private ArrayBlockingQueue<NetSocketImpl> backlog;
        private Binding binding;
        private NetSocketImpl peer;
        /**
         * {@code true} if the SO_REUSEADDR option is enabled, in which case the socket will be allowed to
         * {@link #bind(InetAddress, int)} even if the port is in TCP_WAIT state.
         */
        private boolean soReuseAddr;
        /**
         * When shutdown, {@link OutputStream#write(int)} throws {@link SocketException}. The previously written bytes
         * can be read by the {@link #peer}. Once all bytes are read, peer's {@link InputStream#read()} will return
         * {@code -1} and {@link SocketImpl#available()} will return {@code 0}.
         */
        private boolean isOutputShutdown;
        /**
         * When shutdown, {@link InputStream#read()} returns -1 and {@link SocketImpl#available()} returns 0. This has
         * no impact on the {@link #peer} socket: every data sent by peer will silently be discarded.
         */
        private boolean isInputShutdown;
        /**
         * The time granted to blocking operation to complete (Only for {@link InputStream#read()} and
         * {@link #accept(SocketImpl)}).
         */
        private int soTimeout;
        private Object soLinger;
        private boolean closed;

        NetSocketImpl(boolean isServer) {
            this.isServer = isServer;
            this.receiveBuffer = isServer ? null : new NetBuffer(4096);
            this.connected = isServer ? null : new SynchronousQueue<>();
            this.backlog = isServer ? new ArrayBlockingQueue<>(128) : null;
        }

        @Override
        protected void create(boolean stream) throws IOException {
            if (!stream) {
                throw new IOException("Datagram sockets are not supported");
            }
        }

        @Override
        protected void connect(String host, int port) throws IOException {
            connect(getByName(host), port);
        }

        @Override
        protected void connect(InetAddress address, int port) throws IOException {
            connect(new InetSocketAddress(address, port), 10_000);
        }

        @Override
        protected void connect(SocketAddress toAddress, int timeoutMillis) throws IOException {
            if (!(toAddress instanceof InetSocketAddress peerSocketAddress)) {
                throw new IOException("Unsupported toAddress type");
            } else if (peerSocketAddress.isUnresolved()) {
                throw new UnknownHostException(peerSocketAddress.getHostName());
            }
            try {
                var peerAddress = peerSocketAddress.getAddress();

                if (binding == null) {
                    binding = netInterfaces.bind(
                            peerAddress.isLoopbackAddress() ? getLoopbackAddress()
                                                            : localHost,
                            localport, this, soReuseAddr);
                    localport = binding.port();
                }

                int peerPort = peerSocketAddress.getPort();
                var peerSocket = route(binding.address(), peerAddress, peerPort);
                if (!(peerSocket instanceof NetSocketImpl listeningSocket) || !listeningSocket.isServer) {
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
        protected void bind(InetAddress host, int port) throws IOException {
            binding = netInterfaces.bind(host, port, this, soReuseAddr);
            localport = binding.port();
            if (isServer) {
                address = binding.address();
            }
        }

        @Override
        protected InputStream getInputStream() {
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
                    simulator.lock.lock();
                    try {
                        for (;;) {
                            if (closed) {
                                throw new SocketException("Socket is closed");
                            } else if (isInputShutdown) {
                                return -1;
                            }
                            int readable = Math.min(len, receiveBuffer.readableBytes());
                            if (readable > 0) {
                                // Randomly, perform either a complete or a partial read
                                int read = random.nextBoolean() ? readable : random.nextInt(1, readable + 1);
                                return receiveBuffer.read(b, off, read);
                            } else if (peer.isOutputShutdown) {
                                return -1;
                            } else if (!receiveBuffer.waitIfEmpty(soTimeout)) {
                                throw new SocketTimeoutException();
                            }
                        }
                    } finally {
                        simulator.lock.unlock();
                    }
                }

                @Override
                public int available() {
                    simulator.lock.lock();
                    try {
                        return isInputShutdown ? 0 : receiveBuffer.readableBytes();
                    } finally {
                        simulator.lock.unlock();
                    }
                }

                @Override
                public void close() {
                    shutdownInput();
                }
            };
        }

        @Override
        protected void shutdownInput() {
            simulator.lock.lock();
            try {
                isInputShutdown = true;
                receiveBuffer.signalCanRead();
            } finally {
                simulator.lock.unlock();
            }
        }

        @Override
        protected void shutdownOutput() {
            simulator.lock.lock();
            try {
                isOutputShutdown = true;
                // Unblock local write() so that an exception can be thrown as a result of this shutdown
                peer.receiveBuffer.signalCanWrite();
                // Unblock peer read() so that it can returns -1 if all bytes have been read
                peer.receiveBuffer.signalCanRead();
            } finally {
                simulator.lock.unlock();
            }
        }

        @Override
        protected OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] { (byte) b });
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    Objects.requireNonNull(b);
                    Objects.checkFromIndexSize(off, len, b.length);
                    simulator.lock.lock();
                    try {
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
                            bytesWritten = Math.min(len - totalWritten, peer.receiveBuffer.writeableBytes());
                            if (bytesWritten > 0) {
                                peer.receiveBuffer.write(b, off + totalWritten, bytesWritten);
                            } else {
                                peer.receiveBuffer.waitIfFull();
                            }
                        }
                    } finally {
                        simulator.lock.unlock();
                    }
                    simulateBandwidth();
                }

                private void simulateBandwidth() {
                    long bandwidthMs = random.nextInt(0, 3);
                    try {
                        Thread.sleep(bandwidthMs);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                @Override
                public void close() {
                    NetSocketImpl.this.close();
                }
            };
        }

        @Override
        protected int available() throws IOException {
            simulator.lock.lock();
            try {
                if (closed) {
                    throw new SocketException("socket is closed");
                }
                return isInputShutdown ? -1 : receiveBuffer.readableBytes();
            } finally {
                simulator.lock.unlock();
            }
        }

        @Override
        public void close() {
            simulator.lock.lock();
            try {
                closed = isOutputShutdown = isInputShutdown = true;
                if (binding != null) {
                    binding.close();
                }
                if (isServer) {
                    // unblock local accept()
                    backlog.offer(new NetSocketImpl(false));
                } else {
                    // Unblock local read()
                    receiveBuffer.signalCanRead();
                    if (peer != null) {
                        // Unblock the local write() so that it can throws exception
                        peer.receiveBuffer.signalCanWrite();
                        // Unblock peer's read() possibly waiting for data which will never come
                        peer.receiveBuffer.signalCanRead();
                    }
                }
            } finally {
                simulator.lock.unlock();
            }
        }

        @Override
        protected void sendUrgentData(int data) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected <T> void setOption(SocketOption<T> name, T value) {
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
                receiveBuffer.trySetCapacity((Integer) value);
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
                return receiveBuffer.capacity();
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
        protected void accept(SocketImpl socket) throws IOException {
            if (!(socket instanceof NetSocketImpl acceptedLocalSocket)) {
                throw new IOException("Not a simulated socket");
            }
            try {
                var peerSocket = soTimeout == 0 ? backlog.take() : backlog.poll(soTimeout, MILLISECONDS);
                if (closed) {
                    throw new SocketException("Socket closed");
                } else if (peerSocket == null) {
                    throw new InterruptedIOException();
                }
                acceptedLocalSocket.binding = new Binding(peerSocket.address, peerSocket.port, () -> { });
                acceptedLocalSocket.localport = localport;
                acceptedLocalSocket.address = peerSocket.binding.address();
                acceptedLocalSocket.port = peerSocket.binding.port();
                acceptedLocalSocket.peer = peerSocket;
                peerSocket.connected.offer(acceptedLocalSocket);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }
    }

    SocketImpl route(InetAddress from, InetAddress address, int port)
            throws UnknownHostException, NoRouteToHostException {
        return netInterfaces.isLocal(address) ? netInterfaces.route(address, port)
                                              : simulator.route(from, address, port);
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

    /**
     * Network interfaces of a host.
     * <p>
     * {@link NetInterfaces} is assigned two IP addresses: the loopback (127.0.0.1) and a localhost provided as
     * an argument. It manages port binding on these addresses and supports binding on the "any" address (0.0.0.0) as
     * well as binding on an ephemeral port.
     * <p>
     * It simulates the {@code TCP_WAIT} state by randomly throwing {@link SocketException} when a binding is
     * made on a free port (once every thousand binds) unless {@code SO_REUSEADDR} is provided.
     */
    private class NetInterfaces {
        private static final int EPHEMERAL_RANGE_START = 32768;
        private static final int EPHEMERAL_RANGE_END = 61000;
        /**
         * List of the IP addresses assigned to the interfaces. Since {@link InetAddress} cannot be used as a
         * key, the IP addresses are in literal format.
         */
        private final Set<String> addresses;
        /** Map of currently bound sockets keyed by hostPort ({@code <literalIP>:<port>}). */
        private final Map<String, SocketImpl> boundSockets = new HashMap<>();

        NetInterfaces(InetAddress localHost) {
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
            if (port == 0) {
                port = random.nextInt(EPHEMERAL_RANGE_START, EPHEMERAL_RANGE_END);
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
            if (boundSockets.containsKey(hostPort) || (!reuseAddress && random.nextInt(1000) == 0)) {
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
