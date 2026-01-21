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
package com.pingidentity.opendst.maven;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.maven.plugin.logging.Log;

/**
 * Centralizes and handles the formatting logic of OpenDST simulation logs.
 * <p>
 * Provides a builder-style API to emit structured logs.
 */
final class OpenDstLogger {
    private static final HexFormat HEX = HexFormat.of();

    private final Log logger;

    OpenDstLogger(Log logger) {
        this.logger = logger;
    }

    LogBuilder run(String type) {
        return new LogBuilder(logger, "run", type);
    }

    LogBuilder signal(String type) {
        return new LogBuilder(logger, "signal", type);
    }

    Log raw() {
        return logger;
    }

    static final class LogBuilder {
        private final Log logger;
        private final String kind;
        private final String type;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private boolean error = false;

        private LogBuilder(Log logger, String kind, String type) {
            this.logger = logger;
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

        LogBuilder withExpectedHash(int hash) {
            attributes.put("expected-hash", HEX.toHexDigits(hash));
            return this;
        }

        LogBuilder withCurrentHash(int hash) {
            attributes.put("current-hash", HEX.toHexDigits(hash));
            return this;
        }

        LogBuilder withCode(int code) {
            attributes.put("code", String.valueOf(code));
            return this;
        }

        LogBuilder withLastLog(Object lastLog) {
            attributes.put("last-log", String.valueOf(lastLog));
            return this;
        }

        void log() {
            var sb = new StringBuilder();
            if ("run".equals(kind)) {
                sb.append("   run type:");
            } else {
                sb.append("signal type:");
            }

            sb.append("%-11s".formatted(type));

            for (var entry : attributes.entrySet()) {
                sb.append(" ").append(entry.getKey()).append(":").append(entry.getValue());
            }

            if (error) {
                logger.error(sb.toString());
            } else {
                logger.info(sb.toString());
            }
        }
    }
}
