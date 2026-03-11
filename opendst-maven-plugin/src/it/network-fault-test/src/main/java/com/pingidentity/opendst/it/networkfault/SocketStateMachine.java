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

import java.util.HashMap;
import java.util.Map;

/**
 * TCP socket state machine oracle for test steering.
 *
 * <p>This class models the lifecycle of TCP sockets (both
 * listeners and connections) as a finite state machine. It is
 * used by {@link NetworkFaultApp} to prevent the chaos test
 * from attempting invalid socket API calls ({@link Tracker#isValid}).
 *
 * <p>Runtime validation of socket semantics is now handled by
 * PObserve monitors in {@link NetworkFaultTraceAuditor}, fed
 * by typed trace events emitted automatically from
 * {@code NodeSocketImpl}.
 *
 * <h2>Listener states</h2>
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
 * <h2>Connection states</h2>
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
 * <p>Per {@link java.net.ServerSocket#setReuseAddress}: this option must be set
 * <em>before</em> binding. When the server periodically restarts (close +
 * re-bind on the same port), it randomly toggles {@code SO_REUSEADDR}.
 * Without it, the bind may fail with {@link java.net.BindException} — which the
 * fault injector can simulate even without actual port contention.
 */
public final class SocketStateMachine {

    /** TCP entity lifecycle states. */
    public enum State { UNBOUND, BOUND, LISTENING, OPEN, INPUT_CLOSED, OUTPUT_CLOSED, BOTH_SHUTDOWN, CLOSED }

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
     * <p>Used by {@link NetworkFaultApp} for test steering:
     * {@link #isValid} prevents the chaos test from attempting
     * invalid socket API calls, and {@link #emit} applies the
     * state transition after a successful call.
     *
     * <p>Trace event emission to stdout has been removed — the
     * simulated network layer ({@code NodeSocketImpl}) now emits
     * typed trace events automatically, and PObserve monitors
     * in {@link NetworkFaultTraceAuditor} validate them.
     */
    public static final class Tracker {

        private final Map<String, State> entities =
                new HashMap<>();

        /**
         * Returns {@code true} if the event is valid for
         * the given entity in its current state.
         */
        public boolean isValid(String id, String event) {
            State cur = entities.get(id);
            if (cur == null) {
                return INITIAL_EVENTS.containsKey(event);
            }
            var allowed = TRANSITIONS.get(cur);
            return allowed != null
                    && allowed.containsKey(event);
        }

        /**
         * Applies the state transition for the given entity.
         * Throws {@link IllegalStateException} if the
         * transition is invalid.
         */
        public State emit(String id, String event) {
            State cur = entities.get(id);
            if (cur == null) {
                State initial = INITIAL_EVENTS.get(event);
                if (initial == null) {
                    throw new IllegalStateException(
                            "%s: first event must be one "
                            + "of %s, got '%s'"
                                    .formatted(
                                            id,
                                            INITIAL_EVENTS
                                                    .keySet(),
                                            event));
                }
                entities.put(id, initial);
                return initial;
            }
            var allowed = TRANSITIONS.get(cur);
            if (allowed == null
                    || !allowed.containsKey(event)) {
                throw new IllegalStateException(
                        "%s: illegal event '%s' in state %s"
                                .formatted(id, event, cur));
            }
            State next = allowed.get(event);
            entities.put(id, next);
            return next;
        }

        /**
         * Returns the current state of the given entity,
         * or {@code null} if unknown.
         */
        public State stateOf(String id) {
            return entities.get(id);
        }
    }

    /**
     * A socket action that may throw {@link java.io.IOException}.
     * Returns the event name to emit (e.g., "message-sent", "message-received", "eof").
     */
    @FunctionalInterface
    public interface SocketAction { String execute() throws java.io.IOException; }

    private SocketStateMachine() {}
}
