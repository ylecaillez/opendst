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
import static tools.jackson.jr.ob.JSON.Feature.WRITE_NULL_PROPERTIES;

import com.pingidentity.opendst.common.AssertType;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.jr.ob.JSON;
import tools.jackson.jr.ob.JacksonJrExtension;
import tools.jackson.jr.ob.api.ExtensionContext;
import tools.jackson.jr.ob.api.ReaderWriterProvider;
import tools.jackson.jr.ob.api.ValueReader;
import tools.jackson.jr.ob.api.ValueWriter;
import tools.jackson.jr.ob.impl.JSONReader;
import tools.jackson.jr.ob.impl.JSONWriter;

/**
 * Centralized constants and utility methods for the OpenDST runner.
 */
public final class Commons {
    private Commons() {}

    /**
     * Shared {@code jackson-jr} instance configured with the project's value
     * conventions:
     * <ul>
     *   <li>{@link Duration} round-trips through ISO-8601 strings.</li>
     *   <li>{@link AssertType} round-trips through its lowercase wire form.</li>
     *   <li>{@code null} record components are omitted on write.</li>
     * </ul>
     */
    public static final JSON JSON_OBJECT = JSON.builder()
            .disable(WRITE_NULL_PROPERTIES)
            .register(new OpenDstExtension())
            .build();

    /**
     * Pretty-printing variant of {@link #JSON_OBJECT}, used to write user-facing
     * artifacts such as {@code report.json}.
     */
    public static final JSON JSON_OBJECT_PRETTY = JSON_OBJECT.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private static final class OpenDstExtension extends JacksonJrExtension {
        @Override
        protected void register(ExtensionContext ctxt) {
            ctxt.appendProvider(new ReaderWriterProvider() {
                @Override
                public ValueReader findValueReader(JSONReader readContext, Class<?> type) {
                    if (Duration.class.isAssignableFrom(type)) {
                        return DURATION_READER;
                    } else if (AssertType.class.isAssignableFrom(type)) {
                        return ASSERT_TYPE_READER;
                    }
                    return null;
                }

                @Override
                public ValueWriter findValueWriter(JSONWriter writeContext, Class<?> type) {
                    if (Duration.class.isAssignableFrom(type)) {
                        return DURATION_WRITER;
                    } else if (AssertType.class.isAssignableFrom(type)) {
                        return ASSERT_TYPE_WRITER;
                    }
                    return null;
                }
            });
        }
    }

    private static final ValueReader DURATION_READER = new ValueReader(Duration.class) {
        @Override
        public Object read(JSONReader reader, JsonParser p) throws JacksonException {
            var value = p.getString();
            return value != null ? Duration.parse(value) : Duration.ZERO;
        }
    };

    private static final ValueWriter DURATION_WRITER = new ValueWriter() {
        @Override
        public void writeValue(JSONWriter context, JsonGenerator g, Object value) {
            g.writeString(value.toString());
        }

        @Override
        public Class<?> valueType() {
            return Duration.class;
        }
    };

    private static final ValueReader ASSERT_TYPE_READER = new ValueReader(AssertType.class) {
        @Override
        public Object read(JSONReader reader, JsonParser p) throws JacksonException {
            return AssertType.fromString(p.getString());
        }
    };

    private static final ValueWriter ASSERT_TYPE_WRITER = new ValueWriter() {
        @Override
        public void writeValue(JSONWriter context, JsonGenerator g, Object value) {
            g.writeString(value.toString());
        }

        @Override
        public Class<?> valueType() {
            return AssertType.class;
        }
    };

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
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+AllowArchivingWithJavaAgent",
            // hashCode=2 uses a deterministic RNG for System.identityHashCode
            "-XX:hashCode=2",
            // Speed up keystore loading for tests
            "-Dkeystore.pkcs12.iterationCount=1",
            "-Dkeystore.pkcs12.keyProtectionIterationCount=1");

    public static final String INSTRUMENTED_APPS_DIR = "instrumented-apps";
    static final String RUN_DIR_FORMAT = "run-%d";

    public static void deleteRecursively(Path basePath, Path directoryToDelete) throws IOException {
        if (!exists(directoryToDelete)) {
            return;
        }
        var absoluteBase = basePath.toAbsolutePath();
        if (absoluteBase.getParent() == null) {
            throw new IllegalArgumentException("The base directory '%s' must not be the root".formatted(basePath));
        } else if (!directoryToDelete.toRealPath().startsWith(absoluteBase)) {
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
