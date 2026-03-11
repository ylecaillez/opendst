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

import static java.lang.Integer.parseInt;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.Thread.sleep;

import com.pingidentity.opendst.TraceEvents;
import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;
import com.pingidentity.opendst.it.networkfault.SocketStateMachine.SocketAction;
import com.pingidentity.opendst.it.networkfault.SocketStateMachine.State;
import com.pingidentity.opendst.it.networkfault.SocketStateMachine.Tracker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DST application that exercises TCP socket semantics under
 * fault injection.
 *
 * <p>Random socket API call sequences are generated on both
 * client and server. The {@link SocketStateMachine.Tracker}
 * is used for test <em>steering</em> only — preventing the
 * chaos test from attempting invalid socket API calls.
 * Actual runtime validation is performed by PObserve monitors
 * in {@link NetworkFaultTraceAuditor}, fed by typed trace
 * events emitted automatically from {@code NodeSocketImpl}.
 */
public final class NetworkFaultApp {

    // ==================== Server ====================

    /**
     * Chaos server that periodically restarts its {@link ServerSocket}, randomly toggling
     * {@code SO_REUSEADDR} and construction patterns.
     *
     * <p>Each listener incarnation is a separate entity in the state machine
     * ({@code srv-0}, {@code srv-1}, ...). Accepted connections are tracked as
     * {@code srv-N-conn-M}.
     *
     * <p>Per {@link ServerSocket#setReuseAddress}, the option must be set
     * before {@link ServerSocket#bind}. Without it, rebinding the same port
     * may fail with {@link BindException} — either from actual TIME_WAIT
     * contention or from the fault injector.
     */
    public static final class Server {
        @SuppressWarnings("InfiniteLoopStatement")
        public static void main(String[] args) throws Exception {
            if (args.length < 1) {
                err.println("Usage: Server <port>");
                exit(1);
            }
            int port = parseInt(args[0]);
            var rng = ThreadLocalRandom.current();
            var t = new Tracker();
            int listenerId = 0;
            int connId = 0;

            while (true) {
                String lid = "srv-" + listenerId++;

                // Try to create and bind a ServerSocket.
                // BindException is expected when SO_REUSEADDR is off.
                ServerSocket ss;
                try {
                    ss = createServerSocket(lid, port, rng, t);
                } catch (BindException e) {
                    Assert.reachable("server-bind-failed", null);
                    t.emit(lid, "error");
                    sleep(100);
                    continue;
                }

                try (ss) {
                    if (listenerId == 1) Signals.ready();

                    int acceptsBeforeRestart = rng.nextInt(1, 20);
                    for (int a = 0; a < acceptsBeforeRestart; a++) {
                        String cid = "srv-" + (listenerId - 1) + "-conn-" + connId++;
                        try {
                            var accepted = ss.accept();
                            t.emit(lid, "accepted");
                            t.emit(cid, "accepted");
                            handleConnection(cid, t, accepted, rng);
                        } catch (IOException e) {
                            rethrowPartition(e);
                            t.emit(lid, "error");
                            sleep(100);
                        }
                    }
                    t.emit(lid, "closed");
                }
                Assert.reachable("server-restart", null);
            }
        }

        /**
         * Creates a {@link ServerSocket} using a random construction pattern.
         *
         * <p>Two patterns exercise the ServerSocket API differently:
         * <ol>
         *   <li><b>Deferred bind</b>: {@code new ServerSocket()} then
         *       {@code setReuseAddress()} then {@code bind()}.</li>
         *   <li><b>Constructor bind</b>: {@code new ServerSocket(port)}.
         *       Binds immediately with the platform default for SO_REUSEADDR.</li>
         * </ol>
         *
         * @throws BindException if the port is already in use (or fault-injected)
         */
        private static ServerSocket createServerSocket(String lid, int port,
                                                       ThreadLocalRandom rng, Tracker t) throws IOException {
            if (rng.nextBoolean()) {
                // Deferred bind — can set SO_REUSEADDR before bind
                Assert.reachable("server-deferred-bind", null);
                var ss = new ServerSocket();
                t.emit(lid, "created");
                boolean reuseAddr = rng.nextBoolean();
                ss.setReuseAddress(reuseAddr);
                Assert.sometimes(reuseAddr, "server-reuse-addr-on", null);
                Assert.sometimes(!reuseAddr, "server-reuse-addr-off", null);
                ss.bind(new InetSocketAddress(port));  // may throw BindException
                t.emit(lid, "bound");
                t.emit(lid, "listening");
                return ss;
            } else {
                // Constructor bind — binds immediately, platform default SO_REUSEADDR
                Assert.reachable("server-constructor-bind", null);
                t.emit(lid, "created");  // emit before constructor so BindException has a valid entity
                var ss = new ServerSocket(port);  // may throw BindException
                t.emit(lid, "bound");
                t.emit(lid, "listening");
                return ss;
            }
        }

        /**
         * Handles an accepted connection with a randomly chosen strategy.
         */
        private static void handleConnection(String id, Tracker t, Socket socket,
                                             ThreadLocalRandom rng) {
            try (socket;
                 var in = socket.getInputStream();
                 var out = socket.getOutputStream()) {

                switch (rng.nextInt(4)) {
                    case 0 -> {
                        Assert.reachable("server-normal-echo", null);
                        echo(id, t, in, out);
                    }
                    case 1 -> {
                        // Per Socket.shutdownOutput(): disables the output stream.
                        // Peer sees EOF. We then drain any remaining input.
                        Assert.reachable("server-output-halfclose", null);
                        echoOnce(id, t, in, out);
                        socket.shutdownOutput();
                        t.emit(id, "output-closed");
                        drain(id, t, in);
                        socket.shutdownInput();
                        t.emit(id, "input-closed");
                    }
                    case 2 -> {
                        // Per Socket.shutdownInput(): any data received after this
                        // is acknowledged then discarded. read() returns -1.
                        Assert.reachable("server-input-halfclose", null);
                        socket.shutdownInput();
                        t.emit(id, "input-closed");
                        out.write("server-initiated".getBytes());
                        t.emit(id, "message-sent");
                    }
                    case 3 -> {
                        Assert.reachable("server-random-sequence", null);
                        randomConnActions(id, t, socket, in, out, rng);
                    }
                }
                // try-with-resources calls socket.close(); emit "closed" if not already
                if (t.stateOf(id) != State.CLOSED) {
                    t.emit(id, "closed");
                }
            } catch (IOException e) {
                try { rethrowPartition(e); } catch (IOException p) { throw new RuntimeException(p); }
                if (t.stateOf(id) != State.CLOSED) {
                    t.emit(id, "error");
                }
            }
        }
    }

    // ==================== Client ====================

    /**
     * Chaos client that connects to the server with random action sequences.
     *
     * <p>Each connection attempt is a separate entity ({@code cli-0}, {@code cli-1}, ...)
     * with its own state machine lifecycle.
     */
    public static final class Client {
        public static void main(String[] args) throws Exception {
            if (args.length < 2) {
                err.println("Usage: Client <host> <port>");
                exit(1);
            }
            String host = args[0];
            int port = parseInt(args[1]);
            var rng = ThreadLocalRandom.current();
            var t = new Tracker();

            for (int i = 0; i < 200; i++) {
                String id = "cli-" + i;

                Socket socket;
                if (rng.nextInt(3) == 0) {
                    // new Socket(host, port) — creates and
                    // connects in one call
                    Assert.reachable(
                            "client-direct-connect", null);
                    t.emit(id, "created");
                    try {
                        socket = new Socket(host, port);
                        t.emit(id, "connected");
                        Assert.reachable(
                                "client-open", null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                        t.emit(id, "error");
                        continue;
                    }
                } else {
                    // new Socket() — unbound, must connect
                    Assert.reachable(
                            "client-unbound", null);
                    socket = new Socket();
                    t.emit(id, "created");
                }

                try {
                    randomClientActions(
                            id, t, socket,
                            host, port, rng);
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                    if (t.stateOf(id) != State.CLOSED) {
                        t.emit(id, "closed");
                    }
                }
            }

            // Signal liveness check: all client operations
            // are complete, sockets are closed. The marker
            // flows through the console capture pipeline to
            // NetworkFaultTraceAuditor.checkLiveness().
            System.out.println(
                    new TraceEvents.TestCompleted()
                            .serialize());
        }
    }

    // ==================== Helpers ====================

    /** Re-throws if the exception is a network partition fault. */
    private static void rethrowPartition(IOException e) throws IOException {
        if (e.getMessage() != null && e.getMessage().contains("network-partition")) {
            throw e;
        }
    }

    /**
     * Tries a socket action against the oracle. Skips if invalid.
     * On success emits the event returned by the action; on IOException
     * emits {@code "error"}.
     *
     * <p>For RECEIVE, pass {@code "message-received"} as nominalEvent and
     * {@code "eof"} as altEvent. The action lambda returns whichever
     * actually occurred.
     */
    private static void tryAction(String id, Tracker t, String nominalEvent,
                                  String altEvent, SocketAction action) throws IOException {
        boolean valid = altEvent != null
                ? t.isValid(id, nominalEvent) || t.isValid(id, altEvent)
                : t.isValid(id, nominalEvent);
        if (!valid) return;

        try {
            t.emit(id, action.execute());
        } catch (IOException e) {
            rethrowPartition(e);
            t.emit(id, "error");
        }
    }

    /**
     * Reads from a stream. Returns {@code "eof"} on end-of-stream,
     * {@code "message-received"} on data.
     */
    private static String read(InputStream in) throws IOException {
        return in.read(new byte[1024]) == -1 ? "eof" : "message-received";
    }

    /** Echo loop: read from peer, write back. Stops on EOF. */
    private static void echo(String id, Tracker t, InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[1024];
        for (;;) {
            int n = in.read(buf);
            if (n == -1) { t.emit(id, "eof"); return; }
            t.emit(id, "message-received");
            out.write(buf, 0, n);
            t.emit(id, "message-sent");
        }
    }

    /** Read one message and echo it back. */
    private static void echoOnce(String id, Tracker t, InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[1024];
        int n = in.read(buf);
        if (n == -1) { t.emit(id, "eof"); return; }
        t.emit(id, "message-received");
        out.write(buf, 0, n);
        t.emit(id, "message-sent");
    }

    /** Drain remaining data until EOF. */
    private static void drain(String id, Tracker t, InputStream in) throws IOException {
        byte[] buf = new byte[1024];
        for (;;) {
            int n = in.read(buf);
            if (n == -1) { t.emit(id, "eof"); return; }
            t.emit(id, "message-received");
        }
    }

    // ==================== Random action sequences ====================

    /**
     * Random actions on a server-side accepted socket.
     */
    private static void randomConnActions(String id, Tracker t, Socket socket,
                                          InputStream in, OutputStream out,
                                          ThreadLocalRandom rng) throws IOException {
        int steps = rng.nextInt(2, 6);
        for (int s = 0; s < steps && t.stateOf(id) != State.CLOSED; s++) {
            switch (rng.nextInt(5)) {
                case 0 -> tryAction(id, t, "message-sent", null,
                        () -> { out.write("pong".getBytes()); return "message-sent"; });
                case 1 -> tryAction(id, t, "message-received", "eof",
                        () -> read(in));
                case 2 -> tryAction(id, t, "input-closed", null,
                        () -> { socket.shutdownInput(); return "input-closed"; });
                case 3 -> tryAction(id, t, "output-closed", null,
                        () -> { socket.shutdownOutput(); return "output-closed"; });
                case 4 -> tryAction(id, t, "closed", null,
                        () -> { socket.close(); return "closed"; });
            }
        }
    }

    /**
     * Random actions on a client socket.
     */
    private static void randomClientActions(String id, Tracker t, Socket socket,
                                            String host, int port,
                                            ThreadLocalRandom rng) throws IOException {
        int steps = rng.nextInt(2, 11);
        for (int s = 0; s < steps && t.stateOf(id) != State.CLOSED; s++) {
            switch (rng.nextInt(7)) {
                case 0 -> tryAction(id, t, "bound", null,
                        () -> { socket.bind(new InetSocketAddress(0)); return "bound"; });
                case 1 -> tryAction(id, t, "connected", null,
                        () -> { socket.connect(new InetSocketAddress(host, port)); return "connected"; });
                case 2 -> tryAction(id, t, "message-sent", null,
                        () -> { socket.getOutputStream().write("ping".getBytes()); return "message-sent"; });
                case 3 -> tryAction(id, t, "message-received", "eof",
                        () -> { String ev = read(socket.getInputStream());
                                Assert.sometimes("eof".equals(ev), "client-eof", null);
                                return ev; });
                case 4 -> tryAction(id, t, "input-closed", null,
                        () -> { socket.shutdownInput(); return "input-closed"; });
                case 5 -> tryAction(id, t, "output-closed", null,
                        () -> { socket.shutdownOutput(); return "output-closed"; });
                case 6 -> tryAction(id, t, "closed", null,
                        () -> { socket.close(); return "closed"; });
            }

            Assert.sometimes(t.stateOf(id) == State.OPEN, "client-open", null);
            Assert.sometimes(t.stateOf(id) == State.INPUT_CLOSED, "client-input-closed", null);
            Assert.sometimes(t.stateOf(id) == State.OUTPUT_CLOSED, "client-output-closed", null);
            Assert.sometimes(t.stateOf(id) == State.BOTH_SHUTDOWN, "client-both-shutdown", null);
        }
    }

    private NetworkFaultApp() {}
}
