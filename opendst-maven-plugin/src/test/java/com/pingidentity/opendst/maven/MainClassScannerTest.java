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

import static com.pingidentity.opendst.maven.MainClassScanner.toHostname;
import static java.util.Locale.ROOT;

import java.util.Map;
import java.util.Random;

/**
 * Surefire POJO tests for {@link MainClassScanner}.
 * Discovered by naming convention; no JUnit/TestNG required.
 */
public class MainClassScannerTest {

    public void testToHostname() {
        Map<String, String> cases = Map.of(
                "App", "app",
                "EasyApp", "easy-app",
                "MyServer", "my-server",
                "MyFooServer", "my-foo-server",
                "FlakyDST", "flaky-dst",
                "MyDSTServer", "my-dst-server",
                "DSTServer", "dst-server",
                "DST", "dst",
                "Http2Client", "http2-client",
                "My_Server", "my.server");

        var failures = new StringBuilder();
        for (var entry : cases.entrySet()) {
            var result = toHostname(entry.getKey());
            if (!result.equals(entry.getValue())) {
                failures.append("\n  toHostname(\"")
                        .append(entry.getKey())
                        .append("\") = \"")
                        .append(result)
                        .append("\", expected \"")
                        .append(entry.getValue())
                        .append('"');
            }
        }

        if (!failures.isEmpty()) {
            throw new AssertionError("toHostname failures:" + failures);
        }
    }

    public void testToHostnameProperties() {
        // Fixed seed for reproducibility; 10 000 trials gives good coverage of the input space.
        var rng = new Random(42L);
        var failures = new StringBuilder();

        for (int t = 0; t < 10_000; t++) {
            var name = generateClassName(rng);
            var result = toHostname(name);

            // Property 1: output contains only lowercase letters and hyphens.
            if (!result.equals(result.toLowerCase(ROOT))) {
                failures.append("\n  [")
                        .append(name)
                        .append("] output has uppercase: ")
                        .append(result);
            }

            // Property 2: no leading or trailing hyphen.
            if (result.startsWith("-") || result.endsWith("-")) {
                failures.append("\n  [")
                        .append(name)
                        .append("] leading/trailing hyphen: ")
                        .append(result);
            }

            // Property 3: characters are preserved — removing separators and mapping '.' back to '_'
            //             gives the lowercased input.
            if (!result.replace("-", "").replace(".", "_").equals(name.toLowerCase(ROOT))) {
                failures.append("\n  [")
                        .append(name)
                        .append("] character mismatch: ")
                        .append(result);
            }

            // Property 4: output is a sequence of alphanumeric segments separated by '-' or '.',
            //             i.e. [a-z0-9]+([.-][a-z0-9]+)* — no empty segments, no consecutive separators.
            if (!result.matches("[a-z0-9]+([.\\-][a-z0-9]+)*")) {
                failures.append("\n  [")
                        .append(name)
                        .append("] malformed output: ")
                        .append(result);
            }
        }

        if (!failures.isEmpty()) {
            throw new AssertionError("Property violations over 10 000 trials:" + failures);
        }
    }

    /**
     * Generates a Java-like simple class name: 1–4 words, each starting with one or more
     * uppercase letters (acronym run), followed by a lowercase/digit tail. Words are occasionally
     * separated by underscores (subdomain boundary) instead of being concatenated directly.
     */
    private static String generateClassName(Random rng) {
        int words = 1 + rng.nextInt(4);
        var sb = new StringBuilder();
        for (int w = 0; w < words; w++) {
            if (w > 0 && rng.nextInt(6) == 0) {
                sb.append('_');
            }
            // Uppercase run: 1 letter most of the time, occasionally an acronym (2–4 letters).
            int upperRun = rng.nextBoolean() ? 1 : 1 + rng.nextInt(3);
            for (int u = 0; u < upperRun; u++) {
                sb.append((char) ('A' + rng.nextInt(26)));
            }
            // Lowercase/digit tail.
            int tail = rng.nextInt(7);
            for (int i = 0; i < tail; i++) {
                if (rng.nextInt(8) == 0) {
                    sb.append((char) ('0' + rng.nextInt(10)));
                } else {
                    sb.append((char) ('a' + rng.nextInt(26)));
                }
            }
        }
        return sb.toString();
    }
}
