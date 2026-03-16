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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Tracing decorator for {@link ServerSocket} that emits
 * {@link TraceEvents.ConnectionEstablished} when a
 * connection is accepted.
 *
 * <p>Uses the 4-tuple registry in {@link TracingSocket} to
 * correlate the accepted socket with the client socket that
 * initiated the connection.
 */
final class TracingServerSocket {

    private final ServerSocket delegate;
    private final String socketId;

    private TracingServerSocket(
            ServerSocket ss, String socketId) {
        this.delegate = ss;
        this.socketId = socketId;
    }

    /**
     * Wrap a {@link ServerSocket} that may or may not be
     * bound yet. The socket ID is assigned immediately using
     * the global counter; the actual address is not included
     * because the socket may not have one yet (deferred bind
     * pattern: {@code new ServerSocket()} then
     * {@code setReuseAddress()} then {@code bind()}).
     */
    static TracingServerSocket wrap(ServerSocket ss) {
        String id = "srv#" + TracingSocket.ID_COUNTER
                .getAndIncrement();
        return new TracingServerSocket(ss, id);
    }

    /**
     * Accept a connection and return a
     * {@link TracingSocket}-wrapped accepted socket.
     *
     * <p>Registers the accepted socket's 4-tuple in the
     * global registry so that the client-side
     * {@link TracingSocket#wrapConnected(Socket)} can look
     * it up and emit
     * {@link TraceEvents.ConnectionEstablished}.
     *
     * <p>Also emits {@link TraceEvents.SocketAccepted} for
     * the accepted socket so that monitors know the socket
     * is valid immediately (before the client's task emits
     * {@code ConnectionEstablished}).
     */
    TracingSocket accept() throws IOException {
        Socket accepted = delegate.accept();

        // Assign an ID for the accepted socket
        String acceptedId = TracingSocket.nextId(accepted);

        // Register the accepted socket's 4-tuple so the
        // client-side decorator can look up the accepted
        // socket ID and server socket ID. The key uses the
        // accepted socket's own 4-tuple; the client will
        // query with its reversed 4-tuple (which matches).
        String key = TracingSocket.fourTupleKey(
                accepted.getLocalAddress().getHostAddress(),
                accepted.getLocalPort(),
                accepted.getInetAddress().getHostAddress(),
                accepted.getPort());
        TracingSocket.FOUR_TUPLE_REGISTRY.put(
                key, acceptedId + "|" + socketId);

        // Announce the accepted socket so NoPhantomData
        // knows it is valid before any data events arrive.
        System.out.println(
                new TraceEvents.SocketAccepted(acceptedId)
                        .serialize());

        return TracingSocket.wrapAccepted(
                accepted, acceptedId);
    }

    void close() throws IOException {
        delegate.close();
    }

    boolean isClosed() {
        return delegate.isClosed();
    }

    ServerSocket delegate() {
        return delegate;
    }

    /**
     * Bind the underlying server socket.
     */
    void bind(InetSocketAddress address) throws IOException {
        delegate.bind(address);
    }

    void setReuseAddress(boolean on)
            throws IOException {
        delegate.setReuseAddress(on);
    }
}
