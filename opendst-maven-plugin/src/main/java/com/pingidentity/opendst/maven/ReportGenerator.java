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

import static com.pingidentity.opendst.maven.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.maven.ReportGenerator.AssertStatus.FAIL;
import static com.pingidentity.opendst.maven.ReportGenerator.AssertStatus.PASS;
import static com.pingidentity.opendst.maven.Signal.AssertSignal.AssertType;
import static com.pingidentity.opendst.maven.Signal.SignalType;
import static java.lang.System.currentTimeMillis;
import static java.nio.file.Files.createDirectories;
import static java.util.Map.entry;
import static java.util.Map.ofEntries;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pingidentity.opendst.Plan;
import com.pingidentity.opendst.maven.Commons.SignalEvent;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.WriterOutput;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.JacksonException;

/**
 * Collects simulation signals and generates static HTML and JSON reports for OpenDST runs.
 * Uses JTE for type-safe, secure-by-default template rendering.
 */
final class ReportGenerator {
    private final Path reportPath;
    private final String simpleTitle;
    private final String subtitle;
    private final Instant startTime;
    private volatile Instant finishTime;
    private final TemplateEngine templateEngine;

    private static final String SIMULATION_STOPPED = "stopped";

    private final Map<String, SignalInfo> properties = new ConcurrentHashMap<>();
    private final AtomicLong totalPlans = new AtomicLong(0);
    private final AtomicLong totalViolations = new AtomicLong(0);

    // Default system health state
    private final AtomicReference<SignalInfo> systemHealth = new AtomicReference<>(
            new SignalInfo(SIMULATION_STOPPED, SignalType.LIFECYCLE, null, 0, -1, "opendst-core", -1, null, null));

    ReportGenerator(Path reportPath, String testName, Set<Instrumentation.DiscoveredProperty> discoveredProperties) {
        this.reportPath = reportPath;
        if (testName.contains("#")) {
            var parts = testName.split("#");
            var fullClass = parts[0];
            this.simpleTitle = fullClass.substring(fullClass.lastIndexOf('.') + 1);
            this.subtitle = testName;
        } else {
            this.simpleTitle = testName;
            this.subtitle = "";
        }
        this.startTime = Instant.now();
        this.templateEngine = TemplateEngine.createPrecompiled(ContentType.Html);

        for (var prop : discoveredProperties) {
            properties.putIfAbsent(
                    prop.message(),
                    new SignalInfo(
                            prop.message(),
                            SignalType.ASSERT,
                            prop.kind(),
                            0,
                            -1,
                            prop.origin(),
                            prop.line(),
                            null,
                            null));
        }
    }

    void onPlanStarted(Plan plan) {
        totalPlans.incrementAndGet();
    }

    void onSignalHit(SignalEvent event) {
        var signal = event.signal();
        var label = signal.message();
        var type = signal.type();

        if (type == SignalType.FAULT) {
            // Faults are for orchestrator guidance only, ignored in report
            return;
        }

        AssertType kind = null;
        boolean passedCheck = true;

        if (signal instanceof Signal.AssertSignal assertSignal) {
            kind = assertSignal.kind();
            passedCheck = assertSignal.condition();
        } else if (signal instanceof Signal.LifecycleSignal) {
            if (SIMULATION_STOPPED.equals(label)) {
                passedCheck = isTerminationSuccessful(event);
            }
        }

        final boolean passed = passedCheck;

        if (!passed) {
            totalViolations.incrementAndGet();
        }

        if (type == SignalType.LIFECYCLE) {
            systemHealth.updateAndGet(current -> current.withHit(event.iteration(), signal.fullJson(), passed));
        } else {
            // ASSERT
            var finalKind = kind;
            properties.compute(label, (k, existing) -> {
                var info = existing != null
                        ? existing
                        : new SignalInfo(k, type, finalKind, 0, -1, "unknown", -1, null, null);

                return info.withHit(event.iteration(), signal.fullJson(), passed);
            });
        }
    }

    private boolean isTerminationSuccessful(SignalEvent event) {
        return event.signal() instanceof Signal.LifecycleSignal ls && "success".equals(ls.reason());
    }

    void finish() {
        this.finishTime = Instant.now();
        saveStaticReport();
    }

    /**
     * Verifies if the simulation was successful based on system health and discovered properties.
     * Throws {@link com.pingidentity.opendst.maven.Orchestrator.SimulationFailureException} if system health is bad
     * or if any "ALWAYS" property failed or if any mandatory property ("ALWAYS" or "SOMETIMES") was never satisfied.
     */
    void checkResults() {
        // check system health
        if (systemHealth.get().status() == FAIL) {
            throw new Orchestrator.SimulationFailureException();
        }

        for (var info : properties.values()) {
            if (info.status() == FAIL) {
                throw new Orchestrator.SimulationFailureException();
            }
        }
    }

    private String formatDuration(long start, long end) {
        long diff = Math.max(0, (end - start) / 1000);
        long h = diff / 3600;
        long m = (diff % 3600) / 60;
        long s = diff % 60;
        return "%02d:%02d:%02d".formatted(h, m, s);
    }

    private void saveStaticReport() {
        try {
            createDirectories(reportPath.getParent());
            // Write HTML report
            try (var writer = new FileWriter(reportPath.toFile())) {
                generateHtml(writer);
            }
            // Write JSON report
            Path jsonPath = reportPath.resolveSibling(
                    reportPath.getFileName().toString().replace(".html", ".json"));
            try (var writer = new FileWriter(jsonPath.toFile())) {
                writeReportStateAsJson(writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save report to " + reportPath + ": " + e.getMessage());
        }
    }

    private record ReportState(
            long totalPlans,
            long violations,
            String uptime,
            boolean failed,
            SignalInfo systemHealth,
            List<SignalInfo> properties) {}

    private Writer writeReportStateAsJson(Writer writer) throws IOException {
        var now = finishTime != null ? finishTime.toEpochMilli() : currentTimeMillis();
        var health = systemHealth.get();
        var sortedProperties = properties.values().stream()
                .sorted(Comparator.comparing(SignalInfo::label))
                .toList();

        boolean hasFailed = health.status() == FAIL || sortedProperties.stream().anyMatch(p -> p.status() == FAIL);

        try {
            JSON_MAPPER.writeValue(
                    writer,
                    new ReportState(
                            totalPlans.get(),
                            totalViolations.get(),
                            formatDuration(startTime.toEpochMilli(), now),
                            hasFailed,
                            health,
                            sortedProperties));
            return writer;
        } catch (JacksonException e) {
            writer.write("{\"error\":\"%s\"}".formatted(e.getMessage()));
            return writer;
        }
    }

    private void generateHtml(Writer writer) throws IOException {
        templateEngine.render(
                "com/pingidentity/opendst/maven/report.jte",
                ofEntries(
                        entry("simpleTitle", simpleTitle),
                        entry("subtitle", subtitle),
                        entry("startTime", startTime.toString()),
                        entry(
                                "stateJson",
                                writeReportStateAsJson(new StringWriter()).toString())),
                new WriterOutput(writer));
    }

    /**
     * Represents the possible statuses of an assertion.
     */
    enum AssertStatus {
        PASS("pass"),
        FAIL("fail");

        private final String value;

        AssertStatus(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Immutable snapshot of information about a specific signal hit during simulation.
     * Acts as a state machine for the signal's status.
     */
    record SignalInfo(
            String label,
            SignalType type,
            AssertType assertType,
            long hitCount,
            long firstIteration,
            String origin,
            int line,
            String firstPassLog,
            String firstFailLog) {

        /**
         * Creates a new SignalInfo representing a hit and computes the next state.
         *
         * @param iteration Current simulation iteration.
         * @param logJson Raw JSON log of the hit.
         * @param passed Whether this specific hit satisfies the property.
         * @return A new immutable SignalInfo with updated status and metrics.
         */
        public SignalInfo withHit(long iteration, String logJson, boolean passed) {
            return new SignalInfo(
                    label,
                    type,
                    assertType,
                    hitCount + 1,
                    firstIteration == -1 ? iteration : firstIteration,
                    origin,
                    line,
                    (passed && firstPassLog == null) ? logJson : firstPassLog,
                    (!passed && firstFailLog == null) ? logJson : firstFailLog);
        }

        /**
         * Determines the final status of this signal.
         *
         * <p>Rules by signal/assert type:
         * <ul>
         *   <li><b>LIFECYCLE</b> — FAIL if termination was unhealthy or never received;
         *       PASS if a healthy termination was recorded.</li>
         *   <li><b>FAULT</b> — always PASS (faults are informational).</li>
         *   <li><b>SOMETIMES</b> — PASS if at least one passing hit was recorded; FAIL otherwise.</li>
         *   <li><b>ALWAYS</b> — FAIL if any hit failed or if never hit; PASS if all hits passed.</li>
         *   <li><b>ALWAYS_OR_UNREACHABLE</b> — like ALWAYS, but never-hit is acceptable (PASS).</li>
         * </ul>
         */
        @JsonProperty("status")
        public AssertStatus status() {
            return switch (type) {
                case LIFECYCLE -> lifecycleStatus();
                case FAULT -> PASS;
                case ASSERT -> assertStatus();
            };
        }

        private AssertStatus lifecycleStatus() {
            if (firstFailLog != null) return FAIL; // unhealthy termination recorded
            return hitCount > 0 ? PASS : FAIL; // healthy termination or never received
        }

        private AssertStatus assertStatus() {
            return switch (assertType) {
                case SOMETIMES -> sometimesStatus();
                case ALWAYS -> alwaysStatus();
                case ALWAYS_OR_UNREACHABLE -> alwaysOrUnreachableStatus();
            };
        }

        /** Needs at least one passing hit. */
        private AssertStatus sometimesStatus() {
            return firstPassLog != null ? PASS : FAIL;
        }

        /** Every hit must pass, and at least one hit is required. */
        private AssertStatus alwaysStatus() {
            if (firstFailLog != null) return FAIL; // violated at least once
            return hitCount > 0 ? PASS : FAIL; // all hits passed, or never reached
        }

        /** Like ALWAYS, but never being reached is acceptable. */
        private AssertStatus alwaysOrUnreachableStatus() {
            return firstFailLog != null ? FAIL : PASS;
        }
    }
}
