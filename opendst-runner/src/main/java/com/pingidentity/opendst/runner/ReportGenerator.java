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

import static com.pingidentity.opendst.runner.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.runner.Signal.AssertSignal.AssertType.SOMETIMES;
import static java.lang.System.currentTimeMillis;
import static java.util.Comparator.comparing;

import com.pingidentity.opendst.runner.ExecutionResult.TrackedAssertion;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * Collects simulation signals and generates static HTML and JSON reports for OpenDST runs.
 * Uses JTE for type-safe, secure-by-default template rendering.
 */
final class ReportGenerator {
    private final Instant startTime;
    private final Set<Assertion> assertions;
    private final Map<String, Examples> examples = new ConcurrentHashMap<>();
    private final AtomicInteger totalPlans = new AtomicInteger();

    ReportGenerator(Set<Assertion> discoveredProperties) {
        this.assertions = Set.copyOf(discoveredProperties);
        this.assertions.forEach(
                assertion -> examples.put(assertion.message(), new Examples(0, List.of(), 0, List.of())));
        this.startTime = Instant.now();
    }

    private record AssertionState(String name, String pass, Examples examples) {}

    private record Example(Path planFile, long iteration, JsonNode details) {}

    private record Examples(int passCount, List<Example> passExamples, int failCount, List<Example> failExamples) {
        Examples add(TrackedAssertion assertion, Path planFile) {
            return new Examples(
                    passCount + assertion.passCount(), appendInto(passExamples, planFile, assertion, true),
                    failCount + assertion.failCount(), appendInto(failExamples, planFile, assertion, false));
        }

        /**
         * Add an example into the provided list if it is interesting enough. Interestingness being established by
         * the iteration at which the assertion has been hit: the sooner, the more interesting.
         */
        private static List<Example> appendInto(
                List<Example> examples, Path planFile, TrackedAssertion assertion, boolean pass) {
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
            newExamples.add(new Example(planFile, iteration, details));
            newExamples.sort(comparing(Example::iteration));
            return List.copyOf(newExamples);
        }
    }

    void addExecutionResult(ExecutionResult executionResult, Path planFile) {
        totalPlans.incrementAndGet();
        executionResult
                .assertionsHit()
                .forEach((name, assertionTrack) ->
                        examples.computeIfPresent(name, (_, examples) -> examples.add(assertionTrack, planFile)));
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

    record ReportState(int count, String duration, List<AssertionState> assertions) {}

    public synchronized void generate(Path reportFile) {
        var state = new ArrayList<AssertionState>();
        for (var assertion : assertions) {
            var hit = examples.get(assertion.message());
            if (hit == null || hit.passCount() == 0 && hit.failCount() == 0) {
                // Never hit at runtime.
                // fail: always(), sometimes() (mustHit — must be reached at least once)
                // pass: alwaysOrUnreachable(), unreachable()
                state.add(
                        new AssertionState(assertion.message(), assertion.kind().mustHit() ? "fail" : "pass", hit));
            } else if (SOMETIMES.equals(assertion.kind())) {
                state.add(new AssertionState(assertion.message(), hit.passCount() == 0 ? "fail" : "pass", hit));
            } else if (hit.failCount() > 0) {
                state.add(new AssertionState(assertion.message(), "fail", hit));
            } else {
                state.add(new AssertionState(assertion.message(), "pass", hit));
            }
        }
        JSON_MAPPER
                .writer()
                .withDefaultPrettyPrinter()
                .writeValue(
                        reportFile,
                        new ReportState(
                                totalPlans.get(),
                                formatDuration(startTime.toEpochMilli(), currentTimeMillis()),
                                List.copyOf(state)));
    }

    private String formatDuration(long start, long end) {
        long diff = Math.max(0, (end - start) / 1000);
        long h = diff / 3600;
        long m = (diff % 3600) / 60;
        long s = diff % 60;
        return "%02d:%02d:%02d".formatted(h, m, s);
    }
}
