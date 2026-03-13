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
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.lang.System.arraycopy;
import static java.lang.Thread.sleep;
import static java.lang.Thread.startVirtualThread;
import static java.net.InetAddress.getByName;
import static java.net.InetAddress.getLoopbackAddress;
import static java.time.Duration.ofNanos;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simulated TCP socket implementation replacing the JDK's platform socket.
 * <p>
 * Supports both client and server sockets, with simulated network buffering,
 * send/receive delays, and TCP FIN signaling through background sender/receiver
 * virtual threads per connection.
 */
@SuppressWarnings({"deprecation", "removal"})
final class NodeSocketImpl extends SocketImpl implements Closeable {
    private final Node node;

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
    /** Set by the local sender() when the peer's shutdownInput() causes an RST to propagate back. */
    private volatile boolean connectionResetByPeer;
    private int soTimeout;

    private Object soLinger;
    private boolean closed;

    NodeSocketImpl(Node node, boolean isServer) {
        this.node = requireNonNull(node);
        this.isServer = isServer;
        this.connected = isServer ? null : new SynchronousQueue<>();
        this.backlog = isServer ? new ArrayBlockingQueue<>(128) : null;
    }

    Node node() {
        return node;
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
                binding = node.bindSocket(
                        peerAddress.isLoopbackAddress() ? getLoopbackAddress() : node.getLocalHost(),
                        localport,
                        this,
                        soReuseAddr);
                localport = binding.port();
            }

            int peerPort = peerSocketAddress.getPort();
            var peerSocket = node.route(binding.address(), peerAddress, peerPort);
            if (!(peerSocket instanceof NodeSocketImpl listeningSocket) || !listeningSocket.isServer) {
                throw new SocketException("Connection refused");
            }
            stableConnection = listeningSocket.node() == node;
            address = peerAddress;
            port = peerPort;
            if (!listeningSocket.backlog.offer(this)) {
                throw new SocketException("Connection refused");
            } else if (timeoutMillis > 0 && (peer = connected.poll(timeoutMillis, MILLISECONDS)) == null) {
                throw new SocketTimeoutException();
            } else if (timeoutMillis == 0) {
                peer = connected.take();
            }
            var latency = node.faultInjector()
                    .setPairLatencyIfNotSet(
                            peerAddress,
                            address,
                            ofNanos((node.faults()
                                                    .network()
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
        binding = node.bindSocket(host, port, this, soReuseAddr);
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
                node.faultInjector().onNetworkReceive();
                var timeout = node.faultInjector().onNetworkTimeout();
                if (!timeout.isZero()) {
                    try {
                        sleep(timeout);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                    throw new SocketTimeoutException("OpenDST read timeout");
                }
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
        // Wake up receiver() (waits on sentBytes) so it exits.
        sentBytes.signal();
        // Wake up sender() (waits on writtenBytes) so it detects isInputShutdown
        // and can propagate RST back to peer if data arrives.
        writtenBytes.signal();
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
                node.faultInjector().onNetworkSend();
                var timeout = node.faultInjector().onNetworkTimeout();
                if (!timeout.isZero()) {
                    try {
                        sleep(timeout);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                    throw new SocketTimeoutException("OpenDST write timeout");
                }
                for (int bytesWritten, totalWritten = 0; totalWritten < len; totalWritten += bytesWritten) {
                    if (closed) {
                        throw new SocketException("Socket is closed");
                    } else if (peer.closed) {
                        throw new SocketException("Connection reset");
                    } else if (isOutputShutdown) {
                        throw new SocketException("Socket output is shutdown");
                    } else if (connectionResetByPeer) {
                        // RST received from peer after it shut down input.
                        throw new SocketException("Connection reset");
                    }
                    bytesWritten = min(len - totalWritten, peer.availableSendBufferForPeer());
                    if (bytesWritten > 0) {
                        peer.receiveBuffer.append(b, off + totalWritten, bytesWritten);
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
                if (closed || peer.isOutputShutdown) {
                    // Socket closed or peer notified that no more data will be sent
                    if (!isInputShutdown && peer.isOutputShutdown) {
                        peer.tcpFINSent = true;
                    }
                    sentBytes.signal();
                    return null;
                }
                // Wait for more bytes from peer (or shutdownInput/close signal)
                writtenBytes.await();
            } else {
                // Peer has written some bytes which needs to be sent
                long twoNanosSec = MILLISECONDS.toNanos(2);
                sleep(ofNanos((twoNanosSec * current().nextInt(0, 1000)) / 1000));
                if (closed) {
                    // Socket was closed during transit — just exit.
                    return null;
                }
                if (isInputShutdown) {
                    // TCP RST: local side shut down input, but peer keeps writing.
                    // The data arriving is discarded and RST propagates back to the peer
                    // after a network round-trip delay.
                    // Note: no DataDiscarded trace here — the discard is NOT silent because
                    // RST notifies the peer. DataDiscarded is reserved for truly silent losses.
                    var rstDelay = node.faultInjector()
                            .networkSendDelay(binding.address(), peer.address, stableConnection);
                    sleep(rstDelay);
                    peer.connectionResetByPeer = true;
                    // Unblock peer's write() which may be waiting on receivedBytes (send buffer full)
                    // or on writtenBytes (in its own sender loop)
                    receivedBytes.signal();
                    peer.writtenBytes.signal();
                    return null;
                }
                sentBytes.set(writtenBytes.get());
            }
        }
    }

    private Void receiver() throws InterruptedException, InterruptedIOException {
        for (; ; ) {
            if (isInputShutdown) {
                return null;
            } else if (node.faultInjector().isDisconnected(address, binding.address())) {
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
                var delay = node.faultInjector()
                        .networkSendDelay(peer.address, address, stableConnection)
                        .plus(node.faultInjector().networkReceiveDelay(peer.address, address, stableConnection));
                sleep(delay);
                receivedBytes.set(bytes);
            }
        }
    }

    @Override
    public int available() throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
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
            backlog.offer(new NodeSocketImpl(node, false));
        } else {
            // Unblock local read(), local sender(), and peer's write()
            sentBytes.signal();
            writtenBytes.signal();
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
            node.simulator()
                    .exitSimulation(
                            INTERNAL_ERROR,
                            new Simulator.SimulationError("The accepted local socket is not a NodeSocketImpl: '%s'"
                                    .formatted(socket.toString())));
            throw new IOException("Not a NodeSocketImpl");
        }
        try {
            var peerSocket = soTimeout == 0 ? backlog.take() : backlog.poll(soTimeout, MILLISECONDS);
            if (closed) {
                throw new SocketException("Socket is closed");
            } else if (peerSocket == null) {
                throw new InterruptedIOException();
            }
            acceptedLocalSocket.binding = new Binding(peerSocket.address, peerSocket.port, () -> {});
            acceptedLocalSocket.localport = localport;
            acceptedLocalSocket.address = peerSocket.binding.address();
            acceptedLocalSocket.port = peerSocket.binding.port();
            acceptedLocalSocket.peer = peerSocket;

            if (node.faultInjector().isDisconnected(address, peerSocket.address)) {
                acceptedLocalSocket.close();
            } else {
                var latency = node.faultInjector()
                        .setPairLatencyIfNotSet(
                                peerSocket.address,
                                address,
                                ofNanos((node.faults()
                                                        .network()
                                                        .cloggingLatencyMaximum()
                                                        .toNanos()
                                                * current().nextInt(1000))
                                        / 1000));
                acceptedLocalSocket.sendBufferSize = toIntExact(max(
                        current().nextLong(0, 5_000_000),
                        25 * (MILLISECONDS.toMicros(2) + NANOSECONDS.toMicros(latency.toNanos()))));
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

    /**
     * A bound network address/port pair with a cleanup action.
     * <p>
     * When closed, the binding invokes its closeable to unbind from the
     * node's network interfaces.
     */
    record Binding(InetAddress address, int port, Closeable closeable) implements Closeable {
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
     * A lock-based variable that supports blocking waits for value changes.
     * <p>
     * Used to coordinate producer-consumer communication in the simulated TCP
     * stack (e.g., tracking how many bytes have been written, sent, received,
     * and read).
     */
    @SuppressWarnings("serial")
    static final class AsyncVar extends ReentrantLock {
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
}
