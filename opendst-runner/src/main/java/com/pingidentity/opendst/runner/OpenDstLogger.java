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
 *
 * <p>When running in a terminal (standalone JAR), output uses ANSI colors and emoji
 * for visual clarity. When piped, redirected, or running under Maven, plain text is used.
 */
public final class OpenDstLogger {
    private static final HexFormat HEX = HexFormat.of();

    // ── ANSI escape codes ──────────────────────────────────────────────
    static final String RESET = "\033[0m";
    static final String BOLD = "\033[1m";
    static final String DIM = "\033[2m";
    static final String RED = "\033[31m";
    static final String GREEN = "\033[32m";
    static final String YELLOW = "\033[33m";
    static final String BLUE = "\033[94m"; // bright blue — standard 34 is unreadable on dark backgrounds
    static final String MAGENTA = "\033[95m"; // bright magenta — standard 35 is too dark on some terminals
    static final String CYAN = "\033[36m";

    // ── Emoji and color mapping for structured log lines ───────────────
    //
    // The emoji column is padded to a fixed display width (EMOJI_COL_WIDTH) so that
    // subsequent columns align regardless of each emoji's actual terminal width.
    //
    // Layout: <emoji padded to 2 cols> <space> <type 12col> <attributes...>

    /** Target display width for the emoji column (in terminal columns). */
    private static final int EMOJI_COL_WIDTH = 2;

    /**
     * Emoji with its terminal display width. Surrogate-pair emoji (e.g. 🎲) are 2 columns;
     * simple Unicode symbols (e.g. ⬆) are 1 column.
     */
    private record Glyph(String emoji, int displayWidth) {}

    private static final Map<String, Glyph> EMOJI = Map.ofEntries(
            Map.entry("run:random-walk", new Glyph("\uD83C\uDFB2", 2)), // 🎲
            Map.entry("run:explore", new Glyph("\uD83D\uDD0D", 2)), // 🔍
            Map.entry("run:check", new Glyph("\uD83D\uDD01", 2)), // 🔁
            Map.entry("run:verified", new Glyph("\u2705", 2)), // ✅
            Map.entry("run:fail", new Glyph("\u274C", 2)), // ❌
            Map.entry("run:settings", new Glyph("\u2699\uFE0F", 1)), // ⚙️
            Map.entry("signal:found", new Glyph("\uD83C\uDD95", 2)), // 🆕
            Map.entry("signal:narrowed", new Glyph("\uD83D\uDCD0", 2)), // 📐
            Map.entry(
                    "signal:improved",
                    new Glyph("\u2B06\uFE0F", 1)), // ⬆️ — most terminals render this as 1 column despite VS16
            Map.entry(
                    "run:progress",
                    new Glyph("\u27A1\uFE0F", 1)) // ➡️ — most terminals render this as 1 column despite VS16
            );

    private static final Map<String, String> TYPE_COLOR = Map.ofEntries(
            Map.entry("run:random-walk", CYAN),
            Map.entry("run:explore", BLUE),
            Map.entry("run:check", DIM),
            Map.entry("run:verified", GREEN),
            Map.entry("run:fail", RED + BOLD),
            Map.entry("run:settings", DIM),
            Map.entry("signal:found", GREEN),
            Map.entry("signal:narrowed", MAGENTA),
            Map.entry("signal:improved", GREEN),
            Map.entry("run:progress", DIM));

    /** Minimal logging sink that decouples OpenDST from Maven's Log interface. */
    public interface Sink {
        boolean isDebugEnabled();

        void debug(CharSequence content);

        void info(CharSequence content);

        void warn(CharSequence content);

        void error(CharSequence content);

        /** Whether this sink supports ANSI colors and emoji. */
        default boolean isColorEnabled() {
            return false;
        }
    }

    private final Sink sink;

    public OpenDstLogger(Sink sink) {
        this.sink = sink;
    }

    /** Creates an OpenDstLogger that writes to stderr, for use outside Maven (e.g. in a built JAR). */
    static OpenDstLogger ofConsole(boolean debug) {
        return new OpenDstLogger(new ConsoleSink(debug));
    }

    /**
     * A {@link Sink} implementation that writes to stderr with optional ANSI colors.
     *
     * <p>Color support is auto-detected: enabled when stderr is connected to a real terminal,
     * disabled when piped or redirected. This avoids ANSI escape sequences in log files.
     */
    private static final class ConsoleSink implements Sink {
        private final boolean debug;
        private final boolean color;

        ConsoleSink(boolean debug) {
            this.debug = debug;
            this.color = System.console() != null;
        }

        @Override
        public boolean isDebugEnabled() {
            return debug;
        }

        @Override
        public boolean isColorEnabled() {
            return color;
        }

        @Override
        public void debug(CharSequence content) {
            if (debug) {
                err.println(color ? DIM + content + RESET : "[DEBUG] " + content);
            }
        }

        @Override
        public void info(CharSequence content) {
            err.println(color ? content : "[INFO] " + content);
        }

        @Override
        public void warn(CharSequence content) {
            err.println(color ? YELLOW + content + RESET : "[WARNING] " + content);
        }

        @Override
        public void error(CharSequence content) {
            err.println(color ? RED + BOLD + content + RESET : "[ERROR] " + content);
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

    /**
     * Wraps a message with the given ANSI color if the underlying sink supports color.
     * Returns the message unchanged when color is disabled (piped, Maven, etc.).
     */
    String colored(String color, String message) {
        return sink.isColorEnabled() ? color + message + RESET : message;
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

        LogBuilder withPassing(int passing, int total) {
            attributes.put("passing", passing + "/" + total);
            return this;
        }

        /** Adds an arbitrary key:value attribute. */
        LogBuilder with(String key, Object value) {
            attributes.put(key, String.valueOf(value));
            return this;
        }

        void log() {
            if (sink.isColorEnabled()) {
                logColor();
            } else {
                logPlain();
            }
        }

        /** Plain text format — used when piped, redirected, or under Maven. */
        private void logPlain() {
            var sb = new StringBuilder();
            sb.append("%-12s".formatted(type));
            appendAttributes(sb, false);

            if (error) {
                sink.error(sb.toString());
            } else {
                sink.info(sb.toString());
            }
        }

        /**
         * Colored format with emoji — used when stderr is a terminal.
         *
         * <p>Layout (column positions measured in terminal columns):
         * <pre>
         *   col 0-1: emoji (2 display columns, padded to EMOJI_COL_WIDTH)
         *   col 2:   space
         *   col 3-14: type, left-aligned in 12 chars (colored)
         *   col 15+: key:value pairs (signal first, keys dim, values normal)
         * </pre>
         */
        private void logColor() {
            var key = kind + ":" + type;
            var glyph = EMOJI.get(key);
            var typeColor = TYPE_COLOR.getOrDefault(key, "");

            var sb = new StringBuilder();
            if (glyph != null) {
                sb.append(glyph.emoji());
                sb.append(" ".repeat(Math.max(0, EMOJI_COL_WIDTH - glyph.displayWidth())));
            } else {
                sb.append(" ".repeat(EMOJI_COL_WIDTH));
            }
            sb.append(' ');
            sb.append(typeColor).append("%-12s".formatted(type)).append(RESET);
            appendAttributes(sb, true);

            // Use info() even for errors — we already have per-element coloring
            sink.info(sb.toString());
        }

        /** Maximum display width for the {@code signal} value in colored mode. */
        private static final int SIGNAL_MAX_WIDTH = 40;

        /**
         * Appends key:value pairs to the output. The {@code "signal"} attribute is always
         * emitted first (if present). In colored mode, keys are dim and signal values are
         * truncated to {@link #SIGNAL_MAX_WIDTH} characters.
         */
        private void appendAttributes(StringBuilder sb, boolean colored) {
            // Emit "signal" first if present
            var signal = attributes.get("signal");
            if (signal != null) {
                appendAttribute(sb, "signal", signal, colored);
            }
            for (var entry : attributes.entrySet()) {
                if ("signal".equals(entry.getKey())) {
                    continue; // already emitted
                }
                appendAttribute(sb, entry.getKey(), entry.getValue(), colored);
            }
        }

        private void appendAttribute(StringBuilder sb, String key, String value, boolean colored) {
            sb.append(' ');
            if (colored) {
                if ("signal".equals(key) && value.length() > SIGNAL_MAX_WIDTH) {
                    value = value.substring(0, SIGNAL_MAX_WIDTH - 3) + "...";
                }
                sb.append(DIM).append(key).append(':').append(RESET);
                sb.append(value);
            } else {
                sb.append(key).append(':').append(value);
            }
        }
    }
}
