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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validates the completeness of OpenDST's JDK instrumentation and generates a compatibility report.
 *
 * <p><b>Why:</b> OpenDST achieves determinism by intercepting non-deterministic JDK APIs (Time,
 * Randomness, I/O, Threads). Since these redirections are performed via bytecode manipulation
 * (Advice or Call-Site), missing a method in the simulated implementation would lead to a
 * {@link NoSuchMethodError} at runtime during simulation. This test ensures that all redirected
 * JDK methods have a corresponding deterministic implementation.
 *
 * <p><b>How:</b>
 * <ul>
 *   <li><b>Advice Audit:</b> Uses reflection to find all classes/methods annotated with
 *       {@link Intercepts}. It verifies that all internal Advice classes are documented.
 *   <li><b>Call-Site Audit:</b> Iterates through {@link SimulatorAgent#REDIRECT_MAP} and
 *       cross-references JDK methods (via reflection) with their deterministic wrappers in
 *       {@code Simulated*} classes.
 *   <li><b>Gap Analysis:</b> Compares the set of available JDK methods with the implemented ones.
 *       New gaps cause the test to fail unless they are explicitly listed in
 *       {@code src/test/resources/known-gaps.txt}.
 * </ul>
 *
 * <p><b>Output:</b> Generates a Markdown report at {@code target/INSTRUMENTATION.md} detailing the
 * current instrumentation status.
 */
class InstrumentationAuditTest {

    private Set<String> acknowledgedGaps;
    private final List<AuditResult> auditResults = new ArrayList<>();

    record AuditResult(
            String jdkClass,
            String member,
            String type,
            boolean matched,
            boolean acknowledged,
            boolean noOp,
            String comment) {}

    @BeforeEach
    void setUp() throws IOException {
        var resource = InstrumentationAuditTest.class.getResourceAsStream("/known-gaps.txt");
        if (resource == null) {
            acknowledgedGaps = new HashSet<>();
        } else {
            try (var reader =
                    new java.io.BufferedReader(new java.io.InputStreamReader(resource, StandardCharsets.UTF_8))) {
                acknowledgedGaps = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toSet());
            }
        }
    }

    @Test
    void runAudit() throws Exception {
        // 1. Audit Advice Redirections (Discovered via @Intercepts annotation)
        auditDiscoveredAdvice();

        // 2. Audit Static Methods Redirections
        for (var entry : SimulatorAgent.REDIRECT_MAP.entrySet()) {
            var jdkClassName = entry.getKey().replace('/', '.');
            if (!Simulator.REDIRECT_STATIC_METHODS_OF.contains(entry.getKey())) {
                continue;
            }

            var jdkClass = Class.forName(jdkClassName);
            var simulatedClass = Class.forName(
                    entry.getValue().packageName() + "." + entry.getValue().displayName());

            auditStaticMethods(jdkClass, simulatedClass);
        }

        // 3. Audit Constructor Redirections
        for (var entry : SimulatorAgent.REDIRECT_MAP.entrySet()) {
            var jdkClassName = entry.getKey().replace('/', '.');
            if (!Simulator.REDIRECT_CONSTRUCTORS_OF.contains(entry.getKey())) {
                continue;
            }

            var jdkClass = Class.forName(jdkClassName);
            var simulatedClass = Class.forName(
                    entry.getValue().packageName() + "." + entry.getValue().displayName());

            auditConstructors(jdkClass, simulatedClass);
        }

        generateReport();

        var unknownGaps = auditResults.stream()
                .filter(r -> !r.matched && !r.acknowledged)
                .map(r -> r.jdkClass + "#" + r.member)
                .collect(Collectors.toList());

        assertTrue(
                unknownGaps.isEmpty(),
                "New gaps detected in instrumentation! Please implement them or add to known-gaps.txt if they are intentional: "
                        + unknownGaps);
    }

    private void auditDiscoveredAdvice() throws Exception {
        var simulatedClasses = List.of(
                Network.class,
                Randomness.class,
                SystemInterceptors.class,
                Threads.class,
                Time.class,
                SignalsImpl.class,
                AssertImpl.class);

        var missingAnnotations = new ArrayList<String>();

        for (var clazz : simulatedClasses) {
            // Check top-level methods for @Intercepts
            for (var method : clazz.getDeclaredMethods()) {
                var annotation = method.getAnnotation(Intercepts.class);
                if (annotation != null) {
                    addAdviceResult(annotation, clazz.getSimpleName());
                }
            }

            // Check inner classes for @Intercepts
            for (var inner : clazz.getDeclaredClasses()) {
                var annotations = inner.getAnnotationsByType(Intercepts.class);
                if (annotations.length > 0) {
                    for (var annotation : annotations) {
                        addAdviceResult(annotation, clazz.getSimpleName() + "$" + inner.getSimpleName());
                    }
                } else if (inner.getSimpleName().endsWith("Advice")
                        || inner.getSimpleName().endsWith("Interceptor")) {
                    missingAnnotations.add(clazz.getSimpleName() + "$" + inner.getSimpleName());
                }
            }
        }

        assertTrue(
                missingAnnotations.isEmpty(),
                "The following advice classes are missing the @Intercepts annotation: " + missingAnnotations);
    }

    private void addAdviceResult(Intercepts annotation, String source) {
        var intercepted = annotation.value();
        var parts = intercepted.split("#");
        var jdkClass = parts[0];
        var member = parts.length > 1 ? parts[1] : "unknown";
        auditResults.add(new AuditResult(
                jdkClass, member, "ADVICE (" + source + ")", true, false, annotation.noOp(), annotation.comment()));
    }

    private void auditStaticMethods(Class<?> jdkClass, Class<?> simulatedClass) {
        for (var method : jdkClass.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())) {
                var memberName = formatMethod(method);
                var fullId = jdkClass.getName() + "#" + memberName;

                boolean matched = hasMatchingMethod(simulatedClass, method);
                boolean acknowledged = acknowledgedGaps.contains(fullId);

                auditResults.add(new AuditResult(
                        jdkClass.getName(), memberName, "CALL_SITE (Static)", matched, acknowledged, false, ""));
            }
        }
    }

    private void auditConstructors(Class<?> jdkClass, Class<?> simulatedClass) {
        var factoryMethodName = "new" + jdkClass.getSimpleName();
        for (var ctor : jdkClass.getConstructors()) {
            if (Modifier.isPublic(ctor.getModifiers())) {
                var memberName = formatConstructor(ctor);
                var fullId = jdkClass.getName() + "#" + memberName;

                boolean matched = hasMatchingConstructorFactory(simulatedClass, factoryMethodName, ctor);
                boolean acknowledged = acknowledgedGaps.contains(fullId);

                auditResults.add(new AuditResult(
                        jdkClass.getName(), memberName, "CALL_SITE (Ctor)", matched, acknowledged, false, ""));
            }
        }
    }

    private boolean hasMatchingMethod(Class<?> targetClass, Method sourceMethod) {
        try {
            var targetMethod = targetClass.getMethod(sourceMethod.getName(), sourceMethod.getParameterTypes());
            return Modifier.isStatic(targetMethod.getModifiers()) && Modifier.isPublic(targetMethod.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private boolean hasMatchingConstructorFactory(Class<?> targetClass, String name, Constructor<?> ctor) {
        try {
            var method = targetClass.getMethod(name, ctor.getParameterTypes());
            return Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private String formatMethod(Method m) {
        var params =
                Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(","));
        return String.format("%s(%s)", m.getName(), params);
    }

    private String formatConstructor(Constructor<?> c) {
        var params =
                Arrays.stream(c.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(","));
        return String.format("<init>(%s)", params);
    }

    private void generateReport() throws IOException {
        var reportPath = Paths.get("target", "INSTRUMENTATION.md");
        Files.createDirectories(reportPath.getParent());

        var sb = new StringBuilder();
        sb.append("# OpenDST API Instrumentation Audit\n\n");
        sb.append("This report lists JDK methods redirected by OpenDST and identifies gaps.\n\n");

        sb.append("| JDK Class | Member | Type | Status | Comment |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n");

        for (var res : auditResults) {
            String status = res.matched ? "✅ MATCH" : (res.acknowledged ? "🟡 ACKNOWLEDGED" : "❌ MISSING");
            if (res.noOp) {
                status += " (No-Op)";
            }
            sb.append(String.format(
                    "| %s | `%s` | %s | %s | %s |\n", res.jdkClass, res.member, res.type, status, res.comment));
        }

        Files.writeString(reportPath, sb.toString());
    }
}
