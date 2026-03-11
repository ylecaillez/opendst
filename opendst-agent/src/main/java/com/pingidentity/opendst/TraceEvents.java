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
package com.pingidentity.opendst;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Map;
import tools.jackson.jr.ob.JSON;

/**
 * Typed trace events emitted by the simulated network layer.
 * <p>
 * Each event corresponds to a P specification monitor event
 * ({@code eSpec_*}) and is serialized to JSON for transport
 * through the vhost console capture pipeline. The test-side
 * {@code TraceEventParser} deserializes these back into typed
 * records, then maps them to PObserve monitor events.
 * <p>
 * This class has no dependency on PObserve — it uses only
 * standard Java types and Jackson-jr (already shaded in the
 * agent) for serialization.
 */
public final class TraceEvents {

    private static final JSON JSON_INSTANCE = JSON.std;

    private TraceEvents() {}

    // -----------------------------------------------------------
    //  Sealed event hierarchy
    // -----------------------------------------------------------

    /** Base type for all trace events. */
    public sealed interface TraceEvent {
        /** Serialize this event to a JSON string. */
        String serialize();
    }

    /** A TCP connection has been established between client
     *  and server via accept(). */
    public record ConnectionEstablished(
            String clientSocket,
            String serverSocket,
            String acceptedSocket
    ) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("ConnectionEstablished", Map.of(
                    "clientSocket", clientSocket,
                    "serverSocket", serverSocket,
                    "acceptedSocket", acceptedSocket));
        }
    }

    /** Data was successfully written to a socket. */
    public record DataWritten(
            String socket, byte[] data
    ) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("DataWritten", Map.of(
                    "socket", socket,
                    "data", bytesToHex(data)));
        }

        // records with byte[] need explicit equals/hashCode
        @Override
        public boolean equals(Object o) {
            return o instanceof DataWritten d
                    && socket.equals(d.socket)
                    && Arrays.equals(data, d.data);
        }

        @Override
        public int hashCode() {
            return 31 * socket.hashCode() + Arrays.hashCode(data);
        }
    }

    /** Data was successfully read from a socket. */
    public record DataRead(
            String socket, byte[] data
    ) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("DataRead", Map.of(
                    "socket", socket,
                    "data", bytesToHex(data)));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DataRead d
                    && socket.equals(d.socket)
                    && Arrays.equals(data, d.data);
        }

        @Override
        public int hashCode() {
            return 31 * socket.hashCode() + Arrays.hashCode(data);
        }
    }

    /** A socket has been closed. */
    public record SocketClosed(String socket) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("SocketClosed",
                    Map.of("socket", socket));
        }
    }

    /** An IOException was raised during a socket operation. */
    public record IOExceptionRaised(
            String socket, String operation
    ) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("IOExceptionRaised", Map.of(
                    "socket", socket,
                    "operation", operation));
        }
    }

    /** Output shutdown completed on a socket. */
    public record ShutdownOutputCompleted(
            String socket
    ) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("ShutdownOutputCompleted",
                    Map.of("socket", socket));
        }
    }

    /** EOF was read on a socket (peer shut down output). */
    public record EOFRead(String socket) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("EOFRead",
                    Map.of("socket", socket));
        }
    }

    /** Connection was reset (peer closed or network fault). */
    public record ConnectionReset(
            String socket
    ) implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("ConnectionReset",
                    Map.of("socket", socket));
        }
    }

    /** Marker emitted at end of test to trigger liveness
     *  checking. */
    public record TestCompleted() implements TraceEvent {
        @Override
        public String serialize() {
            return toJson("TestCompleted", Map.of());
        }
    }

    // -----------------------------------------------------------
    //  Parse
    // -----------------------------------------------------------

    private static final String TRACE_PREFIX = "{\"trace\":\"";

    /**
     * Parse a log message into a trace event.
     *
     * @return the parsed event, or {@code null} if the message
     *         is not a trace event
     */
    public static TraceEvent parse(String message) {
        if (message == null
                || !message.startsWith(TRACE_PREFIX)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>)
                JSON_INSTANCE.anyFrom(message);
        var trace = (String) map.get("trace");
        return switch (trace) {
            case "ConnectionEstablished" ->
                    new ConnectionEstablished(
                            str(map, "clientSocket"),
                            str(map, "serverSocket"),
                            str(map, "acceptedSocket"));
            case "DataWritten" ->
                    new DataWritten(
                            str(map, "socket"),
                            hexToBytes(str(map, "data")));
            case "DataRead" ->
                    new DataRead(
                            str(map, "socket"),
                            hexToBytes(str(map, "data")));
            case "SocketClosed" ->
                    new SocketClosed(str(map, "socket"));
            case "IOExceptionRaised" ->
                    new IOExceptionRaised(
                            str(map, "socket"),
                            str(map, "operation"));
            case "ShutdownOutputCompleted" ->
                    new ShutdownOutputCompleted(
                            str(map, "socket"));
            case "EOFRead" ->
                    new EOFRead(str(map, "socket"));
            case "ConnectionReset" ->
                    new ConnectionReset(str(map, "socket"));
            case "TestCompleted" ->
                    new TestCompleted();
            default -> null;
        };
    }

    // -----------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------

    private static String str(Map<String, Object> map,
                              String key) {
        return (String) map.get(key);
    }

    /**
     * Build a JSON string with a {@code "trace"} discriminator
     * followed by the given fields.
     */
    private static String toJson(String trace,
                                 Map<String, String> fields) {
        var buf = new ByteArrayOutputStream(128);
        try (var gen = JSON_INSTANCE.createGenerator(buf)) {
            gen.writeStartObject();
            gen.writeStringProperty("trace", trace);
            for (var e : fields.entrySet()) {
                gen.writeStringProperty(e.getKey(),
                        e.getValue());
            }
            gen.writeEndObject();
        }
        return buf.toString();
    }

    private static final char[] HEX =
            "0123456789abcdef".toCharArray();

    static String bytesToHex(byte[] bytes) {
        var chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(chars);
    }

    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        var bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) (
                    (Character.digit(hex.charAt(i), 16) << 4)
                  + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }
}
