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
package com.pingidentity.opendst.runner;

import static java.lang.System.err;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralizes and handles the formatting logic of OpenDST simulation logs.
 * <p>
 * Provides a builder-style API to emit structured logs. Uses a simple {@link Sink} abstraction
 * instead of Maven's {@code Log} so that the same logger works both inside Maven and
 * in a standalone {@code java -jar} execution.
 */
public final class OpenDstLogger {
    private static final HexFormat HEX = HexFormat.of();

    /** Minimal logging sink that decouples OpenDST from Maven's Log interface. */
    public interface Sink {
        boolean isDebugEnabled();

        void debug(CharSequence content);

        void info(CharSequence content);

        void warn(CharSequence content);

        void error(CharSequence content);
    }

    private final Sink sink;

    public OpenDstLogger(Sink sink) {
        this.sink = sink;
    }

    /** Creates an OpenDstLogger that writes to stderr, for use outside Maven (e.g. in a built JAR). */
    static OpenDstLogger ofConsole() {
        return new OpenDstLogger(new ConsoleSink());
    }

    /** A simple {@link Sink} implementation that writes to stderr. */
    private static final class ConsoleSink implements Sink {
        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(CharSequence content) {
            // No-op: debug output is suppressed in standalone mode
        }

        @Override
        public void info(CharSequence content) {
            err.println("[INFO] " + content);
        }

        @Override
        public void warn(CharSequence content) {
            err.println("[WARNING] " + content);
        }

        @Override
        public void error(CharSequence content) {
            err.println("[ERROR] " + content);
        }
    }

    LogBuilder run(String type) {
        return new LogBuilder(sink, "run", type);
    }

    LogBuilder signal(String type) {
        return new LogBuilder(sink, "signal", type);
    }

    public Sink raw() {
        return sink;
    }

    static final class LogBuilder {
        private final Sink sink;
        private final String kind;
        private final String type;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private boolean error = false;

        private LogBuilder(Sink sink, String kind, String type) {
            this.sink = sink;
            this.kind = kind;
            this.type = type;
        }

        LogBuilder error() {
            this.error = true;
            return this;
        }

        LogBuilder withSeed(long seed) {
            attributes.put("seed", HEX.toHexDigits(seed));
            return this;
        }

        LogBuilder withHash(int hash) {
            attributes.put("hash", HEX.toHexDigits(hash));
            return this;
        }

        LogBuilder withDuration(long duration) {
            attributes.put("duration", String.valueOf(duration));
            return this;
        }

        LogBuilder withScore(double score) {
            attributes.put("score", "%.2f".formatted(score));
            return this;
        }

        LogBuilder withStartingAt(long iteration) {
            attributes.put("starting-at", String.valueOf(iteration));
            return this;
        }

        LogBuilder withSignal(String signal) {
            attributes.put("signal", signal);
            return this;
        }

        LogBuilder withLabel(String label) {
            attributes.put("label", label);
            return this;
        }

        LogBuilder withIteration(long iteration) {
            attributes.put("iteration", String.valueOf(iteration));
            return this;
        }

        LogBuilder withWas(long was) {
            attributes.put("was", String.valueOf(was));
            return this;
        }

        LogBuilder withDistance(double distance) {
            attributes.put("distance", "%.4f".formatted(distance));
            return this;
        }

        void log() {
            var sb = new StringBuilder();
            if ("run".equals(kind)) {
                sb.append(error ? "  run type:" : "   run type:");
            } else {
                sb.append("signal type:");
            }

            sb.append("%-11s".formatted(type));

            for (var entry : attributes.entrySet()) {
                sb.append(" ").append(entry.getKey()).append(":").append(entry.getValue());
            }

            if (error) {
                sink.error(sb.toString());
            } else {
                sink.info(sb.toString());
            }
        }
    }
}
