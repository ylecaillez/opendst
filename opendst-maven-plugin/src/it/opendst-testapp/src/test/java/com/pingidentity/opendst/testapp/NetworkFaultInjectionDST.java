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
package com.pingidentity.opendst.testapp;

import static com.pingidentity.opendst.api.Simulator.startNode;
import static java.lang.Thread.sleep;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.LogMonitor;
import com.pingidentity.opendst.api.Signals;

/**
 * DST that verifies TCP socket semantics under fault injection.
 *
 * <p>Random socket API call sequences are generated on both client and server,
 * and each outcome is validated against a state-machine oracle. Structured
 * {@code [event]} lines are emitted so the {@link LogMonitor} validates
 * transitions from outside the simulation.
 *
 * <h2>State machine</h2>
 *
 * <p><b>Listener</b> ({@code srv-N}):
 * <pre>
 *   (new)      --created-->    UNBOUND
 *   (new)      --bound-->      BOUND
 *   UNBOUND    --bound-->      BOUND
 *   BOUND      --listening-->  LISTENING
 *   LISTENING  --accepted-->   LISTENING
 *   LISTENING  --error-->      LISTENING
 *   LISTENING  --closed-->     CLOSED
 * </pre>
 *
 * <p><b>Connection</b> ({@code srv-N-conn-M}, {@code cli-N}):
 * <pre>
 *   (new)          --created-->          UNBOUND
 *   (new)          --connected-->        OPEN
 *   (new)          --accepted-->         OPEN
 *   UNBOUND        --bound-->            BOUND
 *   UNBOUND        --connected-->        OPEN
 *   BOUND          --connected-->        OPEN
 *
 *   OPEN           --message-sent-->     OPEN
 *   OPEN           --message-received--> OPEN
 *   OPEN           --eof-->              OPEN
 *   OPEN           --input-closed-->     INPUT_CLOSED
 *   OPEN           --output-closed-->    OUTPUT_CLOSED
 *   OPEN           --error-->            CLOSED
 *   OPEN           --closed-->           CLOSED
 *
 *   INPUT_CLOSED   --message-sent-->     INPUT_CLOSED
 *   INPUT_CLOSED   --eof-->              INPUT_CLOSED
 *   INPUT_CLOSED   --output-closed-->    BOTH_SHUTDOWN
 *   INPUT_CLOSED   --error-->            CLOSED
 *   INPUT_CLOSED   --closed-->           CLOSED
 *
 *   OUTPUT_CLOSED  --message-received--> OUTPUT_CLOSED
 *   OUTPUT_CLOSED  --eof-->              OUTPUT_CLOSED
 *   OUTPUT_CLOSED  --input-closed-->     BOTH_SHUTDOWN
 *   OUTPUT_CLOSED  --error-->            CLOSED
 *   OUTPUT_CLOSED  --closed-->           CLOSED
 *
 *   BOTH_SHUTDOWN  --eof-->              BOTH_SHUTDOWN
 *   BOTH_SHUTDOWN  --error-->            CLOSED
 *   BOTH_SHUTDOWN  --closed-->           CLOSED
 * </pre>
 *
 * <h2>SO_REUSEADDR</h2>
 *
 * <p>Per {@link ServerSocket#setReuseAddress}: this option must be set
 * <em>before</em> binding. When the server periodically restarts (close +
 * re-bind on the same port), it randomly toggles {@code SO_REUSEADDR}.
 * Without it, the bind may fail with {@link BindException} — which the
 * fault injector can simulate even without actual port contention.
 */
public class NetworkFaultInjectionDST implements LogMonitor {

    // ==================== State machine ====================

    enum State { UNBOUND, BOUND, LISTENING, OPEN, INPUT_CLOSED, OUTPUT_CLOSED, BOTH_SHUTDOWN, CLOSED }

    /** Events that create a new entity and set its initial state. */
    static final Map<String, State> INITIAL_EVENTS = Map.of(
            "created",   State.UNBOUND,
            "bound",     State.BOUND,
            "connected", State.OPEN,
            "accepted",  State.OPEN);

    /** Valid transitions: current state + event -> next state. */
    static final Map<State, Map<String, State>> TRANSITIONS = Map.of(
            State.UNBOUND, Map.of(
                    "bound",             State.BOUND,
                    "connected",         State.OPEN,
                    "error",             State.CLOSED,
                    "closed",            State.CLOSED),
            State.BOUND, Map.of(
                    "listening",         State.LISTENING,
                    "connected",         State.OPEN,
                    "error",             State.CLOSED,
                    "closed",            State.CLOSED),
            State.LISTENING, Map.of(
                    "accepted",          State.LISTENING,
                    "error",             State.LISTENING,
                    "closed",            State.CLOSED),
            State.OPEN, Map.of(
                    "message-sent",      State.OPEN,
                    "message-received",  State.OPEN,
                    "eof",               State.OPEN,
                    "input-closed",      State.INPUT_CLOSED,
                    "output-closed",     State.OUTPUT_CLOSED,
                    "error",             State.CLOSED,
                    "closed",            State.CLOSED),
            State.INPUT_CLOSED, Map.of(
                    "message-sent",      State.INPUT_CLOSED,
                    "eof",               State.INPUT_CLOSED,
                    "output-closed",     State.BOTH_SHUTDOWN,
                    "error",             State.CLOSED,
                    "closed",            State.CLOSED),
            State.OUTPUT_CLOSED, Map.of(
                    "message-received",  State.OUTPUT_CLOSED,
                    "eof",              State.OUTPUT_CLOSED,
                    "input-closed",      State.BOTH_SHUTDOWN,
                    "error",             State.CLOSED,
                    "closed",            State.CLOSED),
            State.BOTH_SHUTDOWN, Map.of(
                    "eof",               State.BOTH_SHUTDOWN,
                    "error",             State.CLOSED,
                    "closed",            State.CLOSED));

    /**
     * Tracks per-entity state and validates event transitions.
     *
     * <p>{@link #emit} combines stdout event emission with state transition
     * so callers never need to keep the two in sync. {@link #applyTransition}
     * validates without emitting (used by the {@link LogMonitor}).
     */
    static final class Tracker {
        static final String EVENT_PREFIX = "[event] ";
        private final Map<String, State> entities = new HashMap<>();

        boolean isValid(String id, String event) {
            State cur = entities.get(id);
            if (cur == null) return INITIAL_EVENTS.containsKey(event);
            var allowed = TRANSITIONS.get(cur);
            return allowed != null && allowed.containsKey(event);
        }

        /** For RECEIVE duality: valid if either "message-received" or "eof" is accepted. */
        boolean canReceive(String id) {
            return isValid(id, "message-received") || isValid(id, "eof");
        }

        State emit(String id, String event) {
            System.out.println(EVENT_PREFIX + id + " " + event);
            return applyTransition(id, event);
        }

        State stateOf(String id) { return entities.get(id); }

        State applyTransition(String id, String event) {
            State cur = entities.get(id);
            if (cur == null) {
                State initial = INITIAL_EVENTS.get(event);
                if (initial == null)
                    throw new IllegalStateException(
                            "%s: first event must be one of %s, got '%s'"
                                    .formatted(id, INITIAL_EVENTS.keySet(), event));
                entities.put(id, initial);
                return initial;
            }
            var allowed = TRANSITIONS.get(cur);
            if (allowed == null || !allowed.containsKey(event))
                throw new IllegalStateException(
                        "%s: illegal event '%s' in state %s".formatted(id, event, cur));
            State next = allowed.get(event);
            entities.put(id, next);
            return next;
        }
    }

    // ==================== LogMonitor ====================

    private final Tracker monitor = new Tracker();

    @Override
    public void process(Log log) throws Throwable {
        String msg = log.message();
        if (!msg.startsWith(Tracker.EVENT_PREFIX)) return;

        String payload = msg.substring(Tracker.EVENT_PREFIX.length());
        int space = payload.indexOf(' ');
        try {
            monitor.applyTransition(payload.substring(0, space), payload.substring(space + 1));
        } catch (IllegalStateException e) {
            throw new AssertionError(e.getMessage(), e);
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
     * emits {@code "error"} (the operation failed, nothing was sent/received).
     *
     * <p>For RECEIVE, pass {@code "message-received"} as nominalEvent and
     * {@code "eof"} as altEvent. The action lambda returns whichever
     * actually occurred.
     */
    @FunctionalInterface
    interface SocketAction { String execute() throws IOException; }

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
     * Reads from a stream. Returns "eof" on end-of-stream,
     * "message-received" on data. Per {@link InputStream#read(byte[])},
     * returns -1 at end of stream; also returns -1 after
     * {@link Socket#shutdownInput()} per {@link Socket#shutdownInput()}.
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

    // ==================== Test entry point ====================

    public void run() throws IOException {
        startNode("server", "10.0.0.1", this::chaosServer);
        startNode("client", "10.0.0.2", this::chaosClient);
    }

    // ==================== Server ====================

    /**
     * Server that periodically restarts its ServerSocket, randomly toggling
     * SO_REUSEADDR and construction patterns. Each listener incarnation is
     * a separate entity in the state machine ({@code srv-0}, {@code srv-1}, ...).
     *
     * <p>Per {@link ServerSocket#setReuseAddress}, the option must be set
     * before {@link ServerSocket#bind}. Without it, rebinding the same port
     * may fail with {@link BindException} — either from actual TIME_WAIT
     * contention or from the fault injector.
     */
    private Void chaosServer() {
        try {
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
                    ss = createServerSocket(lid, rng, t);
                } catch (BindException e) {
                    // Bind failed — entity was created but never bound.
                    // Transition to CLOSED (via error) and retry.
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a ServerSocket using a random construction pattern.
     *
     * <p>Two patterns exercise the {@link ServerSocket} API differently:
     * <ol>
     *   <li><b>Deferred bind</b>: {@code new ServerSocket()} then
     *       {@code setReuseAddress()} then {@code bind()}. This is the
     *       only way to control SO_REUSEADDR before binding.</li>
     *   <li><b>Constructor bind</b>: {@code new ServerSocket(port)}.
     *       Binds immediately with the platform default for SO_REUSEADDR.</li>
     * </ol>
     *
     * @throws BindException if the port is already in use (or fault-injected)
     */
    private static ServerSocket createServerSocket(String lid, ThreadLocalRandom rng,
                                                   Tracker t) throws IOException {
        if (rng.nextBoolean()) {
            // Deferred bind — can set SO_REUSEADDR before bind
            Assert.reachable("server-deferred-bind", null);
            var ss = new ServerSocket();
            t.emit(lid, "created");
            boolean reuseAddr = rng.nextBoolean();
            ss.setReuseAddress(reuseAddr);
            Assert.sometimes(reuseAddr, "server-reuse-addr-on", null);
            Assert.sometimes(!reuseAddr, "server-reuse-addr-off", null);
            ss.bind(new InetSocketAddress(8080));  // may throw BindException
            t.emit(lid, "bound");
            t.emit(lid, "listening");
            return ss;
        } else {
            // Constructor bind — binds immediately, platform default SO_REUSEADDR
            Assert.reachable("server-constructor-bind", null);
            t.emit(lid, "created");  // emit before constructor so BindException has an entity
            var ss = new ServerSocket(8080);  // may throw BindException
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
                    // Per Socket API: both half-closes do NOT close the socket;
                    // close() must still be called.
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

    // ==================== Client ====================

    private Void chaosClient() throws IOException {
        var rng = ThreadLocalRandom.current();
        var t = new Tracker();

        for (int i = 0; i < 200; i++) {
            String id = "cli-" + i;

            Socket socket;
            if (rng.nextInt(3) == 0) {
                // new Socket(host, port) — creates and connects in one call
                Assert.reachable("client-direct-connect", null);
                t.emit(id, "created");
                try {
                    socket = new Socket("10.0.0.1", 8080);
                    t.emit(id, "connected");
                    Assert.reachable("client-open", null);
                } catch (IOException e) {
                    rethrowPartition(e);
                    t.emit(id, "error");
                    continue;
                }
            } else {
                // new Socket() — unbound, must connect explicitly
                Assert.reachable("client-unbound", null);
                socket = new Socket();
                t.emit(id, "created");
            }

            try {
                randomClientActions(id, t, socket, rng);
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                if (t.stateOf(id) != State.CLOSED) {
                    t.emit(id, "closed");
                }
            }
        }
        return null;
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
                                            ThreadLocalRandom rng) throws IOException {
        int steps = rng.nextInt(2, 11);
        for (int s = 0; s < steps && t.stateOf(id) != State.CLOSED; s++) {
            switch (rng.nextInt(7)) {
                case 0 -> tryAction(id, t, "bound", null,
                        () -> { socket.bind(new InetSocketAddress(0)); return "bound"; });
                case 1 -> tryAction(id, t, "connected", null,
                        () -> { socket.connect(new InetSocketAddress("10.0.0.1", 8080)); return "connected"; });
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
}
