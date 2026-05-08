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

import static java.lang.Character.*;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Scans compiled class files under a directory to discover main-class candidates and
 * {@code TraceAuditor} implementors for zero-config mode.
 *
 * <p>All scanning uses the Java ClassFile API ({@code java.lang.classfile}), consistent with
 * existing usage in {@link Instrumentation} and {@link PropertyDiscoverer}.
 */
final class MainClassScanner {

    private static final String TRACE_AUDITOR_INTERNAL_NAME = "com/pingidentity/opendst/sdk/TraceAuditor";

    private static final MethodTypeDesc MAIN_DESCRIPTOR = MethodTypeDesc.of(CD_void, CD_String.arrayType());

    private MainClassScanner() {}

    /**
     * Scans {@code .class} files under {@code classesDir} and returns the fully qualified class
     * names of every class that declares {@code public static void main(String[])}.
     *
     * <p>Results are sorted alphabetically by simple class name (the part after the last {@code .}).
     *
     * @param classesDir the root directory to scan (e.g. {@code target/classes})
     * @return an immutable list of FQCNs, sorted by simple name; empty if none found
     * @throws IOException if reading any {@code .class} file fails
     */
    static List<String> scan(Path classesDir) throws IOException {
        return walkClasses(classesDir, model -> model.methods().stream()
                .anyMatch(m -> m.methodName().stringValue().equals("main")
                        && m.flags().has(AccessFlag.PUBLIC)
                        && m.flags().has(AccessFlag.STATIC)
                        && m.methodTypeSymbol().equals(MAIN_DESCRIPTOR)));
    }

    /**
     * Scans {@code .class} files under {@code classesDir} and returns the fully qualified class
     * names of every class whose direct interface list includes
     * {@code com.pingidentity.opendst.sdk.TraceAuditor}.
     *
     * <p>Results are sorted alphabetically by simple class name for deterministic diagnostic output.
     *
     * @param classesDir the root directory to scan (e.g. {@code target/classes})
     * @return an immutable list of FQCNs, sorted by simple name; empty if none found
     * @throws IOException if reading any {@code .class} file fails
     */
    static List<String> scanTraceAuditor(Path classesDir) throws IOException {
        return walkClasses(classesDir, model -> model.interfaces().stream()
                .anyMatch(i -> i.asInternalName().equals(TRACE_AUDITOR_INTERNAL_NAME)));
    }

    /**
     * Walks {@code .class} files under {@code classesDir}, parses each with the ClassFile API,
     * and returns the FQCNs of classes matching {@code predicate}, sorted by simple name.
     */
    private static List<String> walkClasses(Path classesDir, Predicate<ClassModel> predicate) throws IOException {
        assert classesDir != null : "classesDir must not be null";
        assert isDirectory(classesDir) : "classesDir must exist: " + classesDir;
        var cf = ClassFile.of();
        var candidates = new ArrayList<String>();
        try (var stream = walk(classesDir)) {
            for (var it = stream.iterator(); it.hasNext(); ) {
                var path = it.next();
                if (!path.toString().endsWith(".class")) {
                    continue;
                }
                var model = cf.parse(readAllBytes(path));
                if (predicate.test(model)) {
                    candidates.add(model.thisClass().asInternalName().replace('/', '.'));
                }
            }
        }
        candidates.sort(Comparator.comparing(MainClassScanner::simpleName));
        return List.copyOf(candidates);
    }

    /**
     * Converts a simple class name to a hostname-safe service name.
     *
     * <ul>
     *   <li>CamelCase word boundaries become {@code -}: {@code MyServer} → {@code my-server}.</li>
     *   <li>Acronym runs are kept together: {@code FlakyDST} → {@code flaky-dst},
     *       {@code MyDSTServer} → {@code my-dst-server}.</li>
     *   <li>Digits are passed through and treated as word-boundary triggers for the next
     *       uppercase letter: {@code Http2Client} → {@code http2-client}.</li>
     *   <li>Underscores become {@code .} (subdomain separator): {@code My_Server} →
     *       {@code my.server}.</li>
     * </ul>
     *
     * @param simpleClassName the simple (unqualified) class name to convert
     * @return the hostname-safe service name
     */
    static String toHostname(String simpleClassName) {
        assert simpleClassName != null && !simpleClassName.isEmpty() : "simpleClassName must be non-empty";
        var sb = new StringBuilder();
        int len = simpleClassName.length();
        for (int i = 0; i < len; i++) {
            char c = simpleClassName.charAt(i);
            if (c == '_') {
                sb.append('.');
                continue;
            }
            if (isUpperCase(c) && i > 0) {
                char prev = simpleClassName.charAt(i - 1);
                char next = i + 1 < len ? simpleClassName.charAt(i + 1) : 0;
                // Insert hyphen at word boundaries:
                // - after a lowercase letter or digit (Http2Client → http2-client)
                // - at the end of an acronym run before a new word (DSTServer → dst-server)
                if (isLowerCase(prev) || isDigit(prev) || (isUpperCase(prev) && isLowerCase(next))) {
                    sb.append('-');
                }
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** Returns the simple name portion of a fully qualified class name. */
    private static String simpleName(String fqcn) {
        assert fqcn != null && !fqcn.isEmpty() : "fqcn must be non-empty";
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
