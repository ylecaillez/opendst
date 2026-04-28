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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates that every advice/interceptor inner class in the agent's simulated implementations
 * carries an {@link Intercepts} annotation, and emits a Markdown summary of the documented
 * JDK redirections.
 *
 * <p><b>Why:</b> OpenDST achieves determinism by intercepting non-deterministic JDK APIs (Time,
 * Randomness, I/O, Threads) via bytecode advice. Each advice class is documented with
 * {@link Intercepts}, which feeds both this audit and the generated report. Forgetting the
 * annotation on a newly added advice would silently drop it from the documented surface, so
 * this test fails the build if any {@code *Advice}/{@code *Interceptor} inner class is missing
 * one.
 *
 * <p><b>Output:</b> {@code target/INSTRUMENTATION.md} listing every advertised redirection.
 *
 * <p>This test uses the Surefire POJO test runner (no JUnit/TestNG required). Surefire discovers
 * it by naming convention ({@code *Test}) and runs each {@code public void testXxx()} method.
 */
public class InstrumentationAuditTest {

    private final List<AuditResult> auditResults = new ArrayList<>();

    record AuditResult(String jdkClass, String member, String source, boolean noOp, String comment) {}

    public void testAdviceAnnotations() throws Exception {
        var simulatedClasses = List.of(
                NetworkInterceptors.class,
                RandomInterceptors.class,
                SystemInterceptors.class,
                ThreadsInterceptors.class,
                TimeInterceptors.class,
                SignalsImpl.class,
                AssertImpl.class);

        var missingAnnotations = new ArrayList<String>();

        for (var clazz : simulatedClasses) {
            for (var method : clazz.getDeclaredMethods()) {
                var annotation = method.getAnnotation(Intercepts.class);
                if (annotation != null) {
                    addAdviceResult(annotation, clazz.getSimpleName());
                }
            }

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

        generateReport();

        if (!missingAnnotations.isEmpty()) {
            throw new AssertionError(
                    "The following advice classes are missing the @Intercepts annotation: " + missingAnnotations);
        }
    }

    private void addAdviceResult(Intercepts annotation, String source) {
        var intercepted = annotation.value();
        var parts = intercepted.split("#");
        var jdkClass = parts[0];
        var member = parts.length > 1 ? parts[1] : "unknown";
        auditResults.add(new AuditResult(jdkClass, member, source, annotation.noOp(), annotation.comment()));
    }

    private void generateReport() throws IOException {
        var reportPath = Paths.get("target", "INSTRUMENTATION.md");
        Files.createDirectories(reportPath.getParent());

        var sb = new StringBuilder();
        sb.append("# OpenDST API Instrumentation Audit\n\n");
        sb.append("This report lists JDK methods redirected by OpenDST advice.\n\n");
        sb.append("| JDK Class | Member | Source | No-Op | Comment |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n");

        for (var res : auditResults) {
            sb.append(String.format(
                    "| %s | `%s` | %s | %s | %s |\n",
                    res.jdkClass, res.member, res.source, res.noOp ? "yes" : "no", res.comment));
        }

        Files.writeString(reportPath, sb.toString());
    }
}
