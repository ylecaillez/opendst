/*
 * Copyright 2026 Ping Identity Corporation
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
package com.pingidentity.opendst.it.networkfault;

import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracing decorator for {@link Socket} that emits
 * {@link TraceEvents} through {@code System.out}, flowing
 * through the console capture pipeline to the
 * {@link NetworkFaultTraceAuditor}.
 *
 * <p>Intercepts all I/O operations (read, write, close,
 * shutdown, available) and emits the corresponding trace
 * events. {@code ConnectionReset} is detected lazily when
 * an I/O operation throws {@code "Connection reset"}.
 *
 * <p>Socket identity is based on a global counter combined
 * with the socket's 4-tuple. A static registry maps 4-tuples
 * to accepted socket info so that the client-side decorator
 * can look up the peer's ID during
 * {@code wrapConnected()} or {@code connect()}.
 */
final class TracingSocket {

    /** Global counter for unique socket IDs. */
    static final AtomicLong ID_COUNTER =
            new AtomicLong();

    /**
     * Registry mapping 4-tuples to accepted socket info.
     * <p>
     * The key is
     * {@code localAddr:localPort->remoteAddr:remotePort}
     * from the accepted socket's perspective. Populated by
     * {@link TracingServerSocket#accept()}, queried by the
     * client on {@code wrapConnected()} or {@code connect()}
     * using the reversed 4-tuple.
     * <p>
     * Value format: {@code acceptedId|serverSocketId}.
     */
    static final ConcurrentHashMap<String, String>
            FOUR_TUPLE_REGISTRY = new ConcurrentHashMap<>();

    private final Socket delegate;
    private final String socketId;
    private boolean inputShutdown;
    private boolean outputShutdown;
    private boolean closed;

    TracingSocket(Socket socket, String socketId) {
        this.delegate = requireNonNull(socket);
        this.socketId = requireNonNull(socketId);
    }

    /**
     * Wrap an already-connected socket (from
     * {@code new Socket(host, port)}).
     *
     * <p>Queries the 4-tuple registry (populated by
     * {@link TracingServerSocket#accept()}) to find the
     * accepted socket ID and server socket ID, then emits
     * {@link TraceEvents.ConnectionEstablished}.
     */
    static TracingSocket wrapConnected(Socket socket) {
        String id = nextId(socket);
        emit(new TraceEvents.SocketConnected(id));
        emitConnectionEstablished(socket, id);
        return new TracingSocket(socket, id);
    }

    /**
     * Wrap an unbound/unconnected socket.
     * <p>
     * The socket ID is assigned lazily after
     * {@link #connect(InetSocketAddress)} succeeds.
     */
    static TracingSocket wrapUnconnected(Socket socket) {
        return new TracingSocket(socket, "unconnected#"
                + ID_COUNTER.getAndIncrement());
    }

    /**
     * Wrap an accepted socket with a pre-assigned ID.
     * Used by {@link TracingServerSocket#accept()}.
     */
    static TracingSocket wrapAccepted(
            Socket socket, String socketId) {
        return new TracingSocket(socket, socketId);
    }

    // ---- Connection ----

    void connect(InetSocketAddress address)
            throws IOException {
        delegate.connect(address);
        emit(new TraceEvents.SocketConnected(socketId));
        emitConnectionEstablished(delegate, socketId);
    }

    // ---- I/O streams ----

    InputStream getInputStream() throws IOException {
        var inner = delegate.getInputStream();
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] a = new byte[1];
                int n = read(a, 0, 1);
                return (n > 0) ? (a[0] & 0xff) : -1;
            }

            @Override
            public int read(byte[] b, int off, int len)
                    throws IOException {
                try {
                    int n = inner.read(b, off, len);
                    if (n > 0) {
                        var data = new byte[n];
                        arraycopy(b, off, data, 0, n);
                        emit(new TraceEvents.DataRead(
                                socketId, data));
                    } else if (n == -1 && !inputShutdown) {
                        emit(new TraceEvents.EOFRead(
                                socketId));
                    }
                    return n;
                } catch (IOException e) {
                    emitIOException(e, "read");
                    throw e;
                }
            }

            @Override
            public int available() throws IOException {
                try {
                    int count = inner.available();
                    emit(new TraceEvents.AvailableQueried(
                            socketId, count));
                    return count;
                } catch (IOException e) {
                    emitIOException(e, "read");
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                TracingSocket.this.close();
            }
        };
    }

    OutputStream getOutputStream() throws IOException {
        var inner = delegate.getOutputStream();
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b});
            }

            @Override
            public void write(byte[] b, int off, int len)
                    throws IOException {
                try {
                    inner.write(b, off, len);
                    var data = new byte[len];
                    arraycopy(b, off, data, 0, len);
                    emit(new TraceEvents.DataWritten(
                            socketId, data));
                } catch (IOException e) {
                    emitIOException(e, "write");
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                TracingSocket.this.close();
            }
        };
    }

    // ---- Shutdown / close ----

    void shutdownInput() throws IOException {
        try {
            delegate.shutdownInput();
            inputShutdown = true;
            emit(new TraceEvents.ShutdownInputCompleted(
                    socketId));
        } catch (IOException e) {
            rethrowPartition(e);
        }
    }

    void shutdownOutput() throws IOException {
        try {
            delegate.shutdownOutput();
            outputShutdown = true;
            emit(new TraceEvents.ShutdownOutputCompleted(
                    socketId));
        } catch (IOException e) {
            rethrowPartition(e);
        }
    }

    void close() throws IOException {
        if (!closed) {
            closed = true;
            emit(new TraceEvents.SocketClosed(socketId));
        }
        delegate.close();
    }

    // ---- Delegation ----

    boolean isClosed() {
        return delegate.isClosed();
    }

    Socket delegate() {
        return delegate;
    }

    String socketId() {
        return socketId;
    }

    // ---- Helpers ----

    /**
     * Emit a trace event to stdout (captured by
     * {@code ConsoleCapture}).
     */
    private static void emit(TraceEvents.TraceEvent event) {
        System.out.println(event.serialize());
    }

    /**
     * Emit {@link TraceEvents.ConnectionReset} if the
     * exception message indicates a reset, then always emit
     * {@link TraceEvents.IOExceptionRaised}.
     */
    private void emitIOException(
            IOException e, String operation) {
        if (isConnectionReset(e)) {
            emit(new TraceEvents.ConnectionReset(socketId));
        }
        emit(new TraceEvents.IOExceptionRaised(
                socketId, operation));
    }

    private static boolean isConnectionReset(IOException e) {
        return e.getMessage() != null
                && e.getMessage().contains("Connection reset");
    }

    /** Re-throws if the exception is a network partition
     *  fault. */
    private static void rethrowPartition(IOException e)
            throws IOException {
        if (e.getMessage() != null
                && e.getMessage()
                        .contains("network-partition")) {
            throw e;
        }
    }

    static String nextId(Socket socket) {
        return socket.getLocalAddress().getHostAddress()
                + ":" + socket.getLocalPort()
                + "#" + ID_COUNTER.getAndIncrement();
    }

    /**
     * Query the 4-tuple registry for the accepted socket info
     * and emit {@link TraceEvents.ConnectionEstablished}.
     *
     * <p>The client's 4-tuple reversed equals the accepted
     * socket's 4-tuple as registered by
     * {@link TracingServerSocket#accept()}.
     */
    private static void emitConnectionEstablished(
            Socket socket, String clientId) {
        if (!socket.isConnected()) return;
        String reversedKey = fourTupleKey(
                socket.getInetAddress().getHostAddress(),
                socket.getPort(),
                socket.getLocalAddress().getHostAddress(),
                socket.getLocalPort());
        String info = FOUR_TUPLE_REGISTRY.remove(reversedKey);
        if (info != null) {
            String[] parts = info.split("\\|", 2);
            String acceptedId = parts[0];
            String serverSocketId = parts[1];
            emit(new TraceEvents.ConnectionEstablished(
                    clientId, serverSocketId, acceptedId));
        }
    }

    /**
     * Build a 4-tuple key string.
     */
    static String fourTupleKey(
            String localAddr, int localPort,
            String remoteAddr, int remotePort) {
        return localAddr + ":" + localPort
                + "->" + remoteAddr + ":" + remotePort;
    }
}
