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

import static java.lang.Long.parseLong;
import static java.nio.file.Files.delete;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.walk;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofNanos;
import static java.time.Duration.ofSeconds;
import static tools.jackson.core.StreamReadFeature.AUTO_CLOSE_SOURCE;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static tools.jackson.databind.cfg.EnumFeature.WRITE_ENUMS_USING_TO_STRING;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Centralized constants and utility methods for the OpenDST runner.
 */
public final class Commons {
    private Commons() {}

    public static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .disable(FAIL_ON_TRAILING_TOKENS)
            .disable(FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(AUTO_CLOSE_SOURCE)
            .enable(WRITE_ENUMS_USING_TO_STRING)
            .build();

    /**
     * JVM options required for the simulator to function correctly with JDK internals.
     */
    static final List<String> JAVA_BASE_OPTIONS = List.of(
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "-Dnet.bytebuddy.safe=true",
            "-XX:+UnlockExperimentalVMOptions",
            // hashCode=2 uses a deterministic RNG for System.identityHashCode
            "-XX:hashCode=2",
            // Speed up keystore loading for tests
            "-Dkeystore.pkcs12.iterationCount=1",
            "-Dkeystore.pkcs12.keyProtectionIterationCount=1");

    public static final String INSTRUMENTED_WARS_DIR = "instrumented-wars";
    static final String RUN_DIR_FORMAT = "run-%d";

    record SignalEvent(long iteration, Signal signal) {}

    public static void deleteRecursively(Path basePath, Path directoryToDelete) throws IOException {
        if (!exists(directoryToDelete)) {
            return;
        } else if (basePath.toAbsolutePath().getParent() == null) {
            throw new IllegalArgumentException("The base directory '%s' must not be the root".formatted(basePath));
        } else if (!directoryToDelete.toRealPath().startsWith(basePath)) {
            throw new IllegalArgumentException(
                    "Directory '%s' will not be deleted as it is outside '%s'".formatted(directoryToDelete, basePath));
        }
        try (var stream = walk(directoryToDelete)) {
            var paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (var path : paths) {
                try {
                    delete(path);
                } catch (NoSuchFileException ignored) {
                }
            }
        }
    }

    static final class DurationUtils {
        private static final Pattern PATTERN = Pattern.compile("^([+-]?\\d+)([a-zA-Z]+)$");

        public static Duration parse(String input) {
            if (input.startsWith("PT")) {
                return Duration.parse(input); // Handle ISO-8601
            }
            var matcher = PATTERN.matcher(input.trim());
            if (matcher.matches()) {
                long value = parseLong(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();
                switch (unit) {
                    case "ns":
                        return ofNanos(value);
                    case "us":
                        return ofNanos(value * 1000);
                    case "ms":
                        return ofMillis(value);
                    case "s":
                        return ofSeconds(value);
                    case "m":
                        return ofMinutes(value);
                    case "h":
                        return ofHours(value);
                    default:
                        throw new IllegalArgumentException("Unknown unit: " + unit);
                }
            }
            return ofMillis(parseLong(input)); // Default to ms
        }
    }
}
