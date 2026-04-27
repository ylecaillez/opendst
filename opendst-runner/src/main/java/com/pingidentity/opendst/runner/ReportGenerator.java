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

import static com.pingidentity.opendst.common.AssertType.SOMETIMES;
import static com.pingidentity.opendst.runner.Commons.JSON_OBJECT_PRETTY;
import static java.lang.System.currentTimeMillis;
import static java.util.Comparator.comparing;

import com.pingidentity.opendst.common.Assertion;
import com.pingidentity.opendst.runner.RunResult.TrackedAssertion;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects simulation signals and generates a JSON report for OpenDST runs.
 */
final class ReportGenerator {
    private final Path reportDir;
    private final Instant startTime;
    private final Set<Assertion> assertions;
    private final Map<String, Examples> examples = new ConcurrentHashMap<>();
    private final AtomicInteger totalPlans = new AtomicInteger();

    ReportGenerator(Set<Assertion> discoveredProperties, Path reportDir) {
        this.reportDir = reportDir;
        this.assertions = Set.copyOf(discoveredProperties);
        this.assertions.forEach(
                assertion -> examples.put(assertion.message(), new Examples(0, List.of(), 0, List.of())));
        this.startTime = Instant.now();
    }

    private record AssertionState(String name, boolean pass, Examples examples) {}

    private record Example(String plan, long iteration, Map<String, Object> details) {}

    private record Examples(int passCount, List<Example> passExamples, int failCount, List<Example> failExamples) {
        Examples add(TrackedAssertion assertion, Path planFile, Path reportDir) {
            return new Examples(
                    passCount + assertion.passCount(), appendInto(passExamples, planFile, assertion, true, reportDir),
                    failCount + assertion.failCount(), appendInto(failExamples, planFile, assertion, false, reportDir));
        }

        /**
         * Add an example into the provided list if it is interesting enough. Interestingness being established by
         * the iteration at which the assertion has been hit: the sooner, the more interesting.
         */
        private static List<Example> appendInto(
                List<Example> examples, Path planFile, TrackedAssertion assertion, boolean pass, Path reportDir) {
            if (pass && assertion.passCount() == 0 || !pass && assertion.failCount() == 0) {
                // This assertion has not been hit
                return examples;
            }
            long iteration = pass ? assertion.firstPassIteration() : assertion.firstFailIteration();
            if (!examples.isEmpty() && examples.getLast().iteration() <= iteration) {
                // This example is boring regarding the already existing one
                // TODO: This is not ideal as we are possibly accumulating examples of identical execution and
                //  rejecting examples of different execution. We should consider adding the simulator current "hash"
                //  in the logs to check whether two logs are effectively identical.
                return examples;
            }
            var details = pass ? assertion.firstPassDetails() : assertion.firstFailDetails();
            var newExamples = new ArrayList<>(examples);
            if (newExamples.size() >= 3) {
                // We cannot remove the associated plan's file as it might be referenced by another example.
                newExamples.removeLast();
            }
            newExamples.add(new Example(reportDir.relativize(planFile).toString(), iteration, details));
            newExamples.sort(comparing(Example::iteration));
            return List.copyOf(newExamples);
        }
    }

    void addRunResult(RunResult runResult, Path planFile) {
        totalPlans.incrementAndGet();
        runResult
                .assertionsHit()
                .forEach((name, assertionTrack) -> examples.computeIfPresent(
                        name, (_, examples) -> examples.add(assertionTrack, planFile, reportDir)));
    }

    /** Returns {@code true} if any non-SOMETIMES assertion has recorded at least one failure. */
    boolean hasFailures() {
        for (var assertion : assertions) {
            if (SOMETIMES.equals(assertion.kind())) {
                continue;
            }
            var hit = examples.get(assertion.message());
            if (hit != null && hit.failCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if all assertions are currently in a passing state.
     *
     * <p>An assertion is passing when:
     * <ul>
     *   <li>{@code ALWAYS} — hit at least once with zero failures</li>
     *   <li>{@code ALWAYS_OR_UNREACHABLE} — either never hit (vacuous pass) or zero failures</li>
     *   <li>{@code SOMETIMES} — at least one pass observed</li>
     * </ul>
     */
    boolean allPassed() {
        for (var assertion : assertions) {
            if (!isPassing(assertion)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the number of assertions currently in a passing state and the total count.
     * Used for progress reporting (e.g. {@code passing:25/31}).
     */
    int[] passingCount() {
        int passing = 0;
        for (var assertion : assertions) {
            if (isPassing(assertion)) {
                passing++;
            }
        }
        return new int[] {passing, assertions.size()};
    }

    /** Checks whether a single assertion meets its kind-specific passing criteria. */
    private boolean isPassing(Assertion assertion) {
        var hit = examples.get(assertion.message());
        if (hit == null || (hit.passCount() == 0 && hit.failCount() == 0)) {
            return !assertion.kind().mustHit();
        } else if (SOMETIMES.equals(assertion.kind())) {
            return hit.passCount() > 0;
        } else {
            return hit.failCount() == 0;
        }
    }

    record ReportState(int count, String duration, List<AssertionState> assertions) {}

    public synchronized void generate(Path reportFile) {
        var state = new ArrayList<AssertionState>();
        for (var assertion : assertions) {
            var hit = examples.get(assertion.message());
            var noExamples = hit == null || (hit.passCount() == 0 && hit.failCount() == 0);
            state.add(
                    new AssertionState(assertion.message(), isPassing(assertion), noExamples ? null : nullEmpty(hit)));
        }
        try {
            JSON_OBJECT_PRETTY.write(
                    new ReportState(
                            totalPlans.get(),
                            formatDuration(startTime.toEpochMilli(), currentTimeMillis()),
                            List.copyOf(state)),
                    reportFile.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write report to " + reportFile, e);
        }
    }

    /** Replaces empty example lists with {@code null} so they are omitted on serialization. */
    private static Examples nullEmpty(Examples e) {
        return new Examples(
                e.passCount(),
                e.passExamples().isEmpty() ? null : e.passExamples(),
                e.failCount(),
                e.failExamples().isEmpty() ? null : e.failExamples());
    }

    private String formatDuration(long start, long end) {
        long diff = Math.max(0, (end - start) / 1000);
        long h = diff / 3600;
        long m = diff % 3600 / 60;
        long s = diff % 60;
        return "%02d:%02d:%02d".formatted(h, m, s);
    }
}
