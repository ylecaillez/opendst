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

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

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
 * client and server, including deliberately invalid sequences
 * (e.g. write after close, read after shutdown). Every action
 * is attempted and {@link IOException} is caught — the chaos
 * test does not skip any operations.
 *
 * <p>All socket I/O is wrapped with {@link TracingSocket} and
 * {@link TracingServerSocket} decorators, which emit typed
 * trace events through {@code System.out}. These flow through
 * the console capture pipeline to
 * {@link NetworkFaultTraceAuditor}, where PObserve monitors
 * validate TCP socket semantics.
 */
public final class NetworkFaultApp {

    // ==================== Server ====================

    /**
     * Chaos server that periodically restarts its
     * {@link ServerSocket}, randomly toggling
     * {@code SO_REUSEADDR} and construction patterns.
     *
     * <p>Per {@link ServerSocket#setReuseAddress}, the option
     * must be set before {@link ServerSocket#bind}. Without
     * it, rebinding the same port may fail with
     * {@link BindException} — either from actual TIME_WAIT
     * contention or from the fault injector.
     */
    public static final class Server {
        @SuppressWarnings("InfiniteLoopStatement")
        public static void main(String[] args)
                throws Exception {
            if (args.length < 1) {
                err.println("Usage: Server <port>");
                exit(1);
            }
            int port = parseInt(args[0]);
            var rng = ThreadLocalRandom.current();
            int listenerId = 0;

            while (true) {
                // Try to create and bind a ServerSocket.
                // BindException is expected when
                // SO_REUSEADDR is off.
                TracingServerSocket ss;
                try {
                    ss = createServerSocket(port, rng);
                } catch (BindException e) {
                    Assert.reachable(
                            "server-bind-failed", null);
                    sleep(100);
                    continue;
                }

                try {
                    if (listenerId == 0) Signals.ready();
                    listenerId++;

                    int acceptsBeforeRestart =
                            rng.nextInt(1, 20);
                    for (int a = 0;
                         a < acceptsBeforeRestart; a++) {
                        try {
                            var accepted = ss.accept();
                            handleConnection(
                                    accepted, rng);
                        } catch (IOException e) {
                            rethrowPartition(e);
                            sleep(100);
                        }
                    }
                } finally {
                    ss.close();
                }
                Assert.reachable("server-restart", null);
            }
        }

        /**
         * Creates a {@link TracingServerSocket} using a
         * random construction pattern.
         *
         * <p>Two patterns exercise the ServerSocket API
         * differently:
         * <ol>
         *   <li><b>Deferred bind</b>:
         *       {@code new ServerSocket()} then
         *       {@code setReuseAddress()} then
         *       {@code bind()}.</li>
         *   <li><b>Constructor bind</b>:
         *       {@code new ServerSocket(port)}. Binds
         *       immediately with the platform default for
         *       SO_REUSEADDR.</li>
         * </ol>
         *
         * @throws BindException if the port is already in
         *         use (or fault-injected)
         */
        private static TracingServerSocket createServerSocket(
                int port, ThreadLocalRandom rng)
                throws IOException {
            if (rng.nextBoolean()) {
                // Deferred bind — can set SO_REUSEADDR
                // before bind
                Assert.reachable(
                        "server-deferred-bind", null);
                var ss = new ServerSocket();
                var tss = TracingServerSocket.wrap(ss);
                boolean reuseAddr = rng.nextBoolean();
                tss.setReuseAddress(reuseAddr);
                Assert.sometimes(reuseAddr,
                        "server-reuse-addr-on", null);
                Assert.sometimes(!reuseAddr,
                        "server-reuse-addr-off", null);
                tss.bind(new InetSocketAddress(port));
                return tss;
            } else {
                // Constructor bind — binds immediately,
                // platform default SO_REUSEADDR
                Assert.reachable(
                        "server-constructor-bind", null);
                return TracingServerSocket.wrap(
                        new ServerSocket(port));
            }
        }

        /**
         * Handles an accepted connection with a randomly
         * chosen strategy.
         */
        private static void handleConnection(
                TracingSocket ts, ThreadLocalRandom rng) {
            try (var _ = new AutoClose(ts)) {
                var in = ts.getInputStream();
                var out = ts.getOutputStream();

                switch (rng.nextInt(4)) {
                    case 0 -> {
                        Assert.reachable(
                                "server-normal-echo",
                                null);
                        echo(in, out);
                    }
                    case 1 -> {
                        Assert.reachable(
                                "server-output-halfclose",
                                null);
                        echoOnce(in, out);
                        ts.shutdownOutput();
                        drain(in);
                        ts.shutdownInput();
                    }
                    case 2 -> {
                        Assert.reachable(
                                "server-input-halfclose",
                                null);
                        ts.shutdownInput();
                        out.write("server-initiated"
                                .getBytes());
                    }
                    case 3 -> {
                        Assert.reachable(
                                "server-random-sequence",
                                null);
                        randomConnActions(
                                ts, in, out, rng);
                    }
                }
            } catch (IOException e) {
                try {
                    rethrowPartition(e);
                } catch (IOException p) {
                    throw new RuntimeException(p);
                }
            }
        }
    }

    // ==================== Client ====================

    /**
     * Chaos client that connects to the server with random
     * action sequences.
     *
     * <p>Each connection attempt exercises a random subset
     * of socket API calls. Invalid calls (e.g. write after
     * close) are expected — the resulting {@link IOException}
     * is caught and the test continues.
     */
    public static final class Client {
        public static void main(String[] args)
                throws Exception {
            if (args.length < 2) {
                err.println("Usage: Client <host> <port>");
                exit(1);
            }
            String host = args[0];
            int port = parseInt(args[1]);
            var rng = ThreadLocalRandom.current();

            for (int i = 0; i < 200; i++) {
                TracingSocket ts;
                if (rng.nextInt(3) == 0) {
                    // new Socket(host, port) — creates and
                    // connects in one call
                    Assert.reachable(
                            "client-direct-connect", null);
                    try {
                        ts = TracingSocket.wrapConnected(
                                new Socket(host, port));
                        Assert.reachable(
                                "client-open", null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                        continue;
                    }
                } else {
                    // new Socket() — unbound, must connect
                    Assert.reachable(
                            "client-unbound", null);
                    ts = TracingSocket.wrapUnconnected(
                            new Socket());
                }

                try {
                    randomClientActions(
                            ts, host, port, rng);
                } finally {
                    try {
                        ts.close();
                    } catch (IOException ignored) {}
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

    /** Echo loop: read from peer, write back. Stops on
     *  EOF. */
    private static void echo(
            InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[1024];
        for (;;) {
            int n = in.read(buf);
            if (n == -1) return;
            out.write(buf, 0, n);
        }
    }

    /** Read one message and echo it back. */
    private static void echoOnce(
            InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[1024];
        int n = in.read(buf);
        if (n == -1) return;
        out.write(buf, 0, n);
    }

    /** Drain remaining data until EOF. */
    private static void drain(InputStream in)
            throws IOException {
        byte[] buf = new byte[1024];
        while (in.read(buf) != -1) { /* drain */ }
    }

    // ==================== Random action sequences ====

    /**
     * Random actions on a server-side accepted socket.
     *
     * <p>Every action is attempted regardless of socket
     * state. {@link IOException} is caught and the test
     * continues — PObserve monitors validate that the
     * simulated network layer raises exceptions when it
     * should.
     */
    private static void randomConnActions(
            TracingSocket ts, InputStream in,
            OutputStream out, ThreadLocalRandom rng)
            throws IOException {
        int steps = rng.nextInt(2, 6);
        for (int s = 0;
             s < steps && !ts.isClosed(); s++) {
            switch (rng.nextInt(5)) {
                case 0 -> {
                    try {
                        out.write("pong".getBytes());
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 1 -> {
                    try {
                        in.read(new byte[1024]);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 2 -> {
                    try {
                        ts.shutdownInput();
                        Assert.reachable(
                                "server-shutdown-input",
                                null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 3 -> {
                    try {
                        ts.shutdownOutput();
                        Assert.reachable(
                                "server-shutdown-output",
                                null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 4 -> {
                    try {
                        ts.close();
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
            }
        }
    }

    /**
     * Random actions on a client socket.
     *
     * <p>Every action is attempted regardless of socket
     * state. {@link IOException} is caught and the test
     * continues — PObserve monitors validate that the
     * simulated network layer raises exceptions when it
     * should.
     */
    private static void randomClientActions(
            TracingSocket ts, String host, int port,
            ThreadLocalRandom rng) throws IOException {
        int steps = rng.nextInt(2, 11);
        for (int s = 0;
             s < steps && !ts.isClosed(); s++) {
            switch (rng.nextInt(7)) {
                case 0 -> {
                    try {
                        ts.delegate().bind(
                                new InetSocketAddress(0));
                        Assert.reachable(
                                "client-bind", null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 1 -> {
                    try {
                        ts.connect(
                                new InetSocketAddress(
                                        host, port));
                        Assert.reachable(
                                "client-open", null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 2 -> {
                    try {
                        ts.getOutputStream()
                                .write("ping".getBytes());
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 3 -> {
                    try {
                        int n = ts.getInputStream()
                                .read(new byte[1024]);
                        Assert.sometimes(n == -1,
                                "client-eof", null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 4 -> {
                    try {
                        ts.shutdownInput();
                        Assert.reachable(
                                "client-shutdown-input",
                                null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 5 -> {
                    try {
                        ts.shutdownOutput();
                        Assert.reachable(
                                "client-shutdown-output",
                                null);
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
                case 6 -> {
                    try {
                        ts.close();
                    } catch (IOException e) {
                        rethrowPartition(e);
                    }
                }
            }
        }
    }

    /**
     * {@link AutoCloseable} adapter for {@link TracingSocket}
     * to enable try-with-resources.
     */
    private record AutoClose(
            TracingSocket ts) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            ts.close();
        }
    }

    private NetworkFaultApp() {}
}
