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

import static java.util.concurrent.ThreadLocalRandom.current;

import com.pingidentity.opendst.common.Faults;
import com.pingidentity.opendst.common.Plan;
import com.pingidentity.opendst.common.Plan.Segment;
import com.pingidentity.opendst.common.Signal.AssertSignal;
import com.pingidentity.opendst.common.Signal.GuidanceSignal;
import com.pingidentity.opendst.common.Signal.LifecycleSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Produces execution plans for the simulation. Implementations decide how the next plan is chosen — by exploring (guided) or by replaying a saved plan.
 */
interface Planner {

    record ExecutionPlan(Plan plan, Predicate<SignalEvent> interesting) {}

    ExecutionPlan nextPlan();

    /**
     * A planner that uses signals (assertions) to guide the exploration.
     * It branches from interesting states to increase coverage.
     */
    final class GuidedPlanner implements Planner {
        private final OpenDstLogger logger;
        private final long duration;
        private final double branchProbability;
        private final Faults.Config faultsConfig;

        // Exploration map: count of how many times we branched FROM this signal+condition
        private final Map<String, Integer> signalExplorationCount = new ConcurrentHashMap<>();
        // Per-signal condition-balanced state: prefixes and hit counts for true/false outcomes
        private final Map<String, ConditionPrefixes> signalState = new ConcurrentHashMap<>();
        // Minimum distance-to-violation observed per comparative assertion signal
        private final Map<String, Double> signalMinDistance = new ConcurrentHashMap<>();

        /**
         * Tracks two prefixes per signal (one for condition=true, one for condition=false)
         * along with hit counts per condition. This allows the planner to preferentially
         * branch from the minority outcome, balancing exploration across both branches.
         */
        private static final class ConditionPrefixes {
            volatile List<Segment> truePrefix;
            volatile List<Segment> falsePrefix;
            final AtomicInteger trueHits = new AtomicInteger();
            final AtomicInteger falseHits = new AtomicInteger();
        }

        GuidedPlanner(OpenDstLogger logger, long duration, double branchProbability, Faults.Config faultsConfig) {
            this.logger = logger;
            this.duration = duration;
            this.branchProbability = branchProbability;
            this.faultsConfig = faultsConfig;
        }

        @Override
        public synchronized ExecutionPlan nextPlan() {
            if (signalState.isEmpty() || current().nextDouble() > branchProbability) {
                return newRandomWalk();
            }
            // Build a list of branchable candidates: each signal can contribute up to 2
            // candidates (one for condition=true, one for condition=false).
            record BranchCandidate(String signal, boolean condition, List<Segment> prefix, double score) {}
            var candidates = new ArrayList<BranchCandidate>();
            for (var entry : signalState.entrySet()) {
                var label = entry.getKey();
                var state = entry.getValue();
                if (state.truePrefix != null) {
                    double s = score(label, state.truePrefix.size(), state, true);
                    candidates.add(new BranchCandidate(label, true, state.truePrefix, s));
                }
                if (state.falsePrefix != null) {
                    double s = score(label, state.falsePrefix.size(), state, false);
                    candidates.add(new BranchCandidate(label, false, state.falsePrefix, s));
                }
            }
            if (candidates.isEmpty()) {
                return newRandomWalk();
            }

            // Tournament selection: pick best of 10 random candidates
            var selected = candidates.get(current().nextInt(candidates.size()));
            for (int i = 0; i < 10; i++) {
                var candidate = candidates.get(current().nextInt(candidates.size()));
                if (candidate.score() > selected.score()) {
                    selected = candidate;
                }
            }
            var explorationKey = selected.signal() + ":" + selected.condition();
            signalExplorationCount.merge(explorationKey, 1, Integer::sum);
            var prefixSegments = selected.prefix();
            long prefixEnd = prefixSegments.getLast().iteration();

            // For distance-guided signals, rewind the prefix to a random point before the assertion.
            // This explores different paths *leading to* the assertion, which may produce different
            // left/right values and narrow the distance further.
            long branchPoint = prefixEnd;
            if (signalMinDistance.containsKey(selected.signal()) && prefixEnd > 0) {
                branchPoint = current().nextLong(prefixEnd);
                prefixSegments = truncateAt(prefixSegments, branchPoint);
            }

            // Use the full remaining budget from the branch point
            long remainingBudget = duration - branchPoint;
            if (remainingBudget <= 0) {
                return newRandomWalk();
            }

            var segments = new ArrayList<>(prefixSegments);
            long seed = current().nextLong();
            segments.add(new Segment(seed, branchPoint + remainingBudget));
            var exploratoryPlan = new Plan(segments, faultsConfig);
            logger.run("explore")
                    .withSignal(explorationKey)
                    .withScore(selected.score())
                    .withStartingAt(branchPoint)
                    .log();
            return new ExecutionPlan(exploratoryPlan, new GuidanceMonitor(exploratoryPlan));
        }

        private ExecutionPlan newRandomWalk() {
            long seed = current().nextLong();
            logger.run("random-walk").withSeed(seed).log();
            var plan = new Plan(List.of(new Segment(seed, duration)), faultsConfig);
            return new ExecutionPlan(plan, new GuidanceMonitor(plan));
        }

        private double score(String signal, int depth, ConditionPrefixes state, boolean condition) {
            var explorationKey = signal + ":" + condition;
            int explorationHeat = signalExplorationCount.getOrDefault(explorationKey, 0);
            // Use exponential scoring for depth to strongly favor progress
            double base = Math.pow(10, depth) / (1.0 + explorationHeat);
            // Boost signals that are close to violation (small distance = high boost)
            var distance = signalMinDistance.get(signal);
            if (distance != null) {
                // distanceBoost: approaches 10x at distance=0, decays toward 1x as distance grows
                double distanceBoost = 1.0 + 9.0 / (1.0 + distance);
                base *= distanceBoost;
            }
            // Boost the minority condition outcome to balance exploration.
            // If one outcome is observed much less frequently, boost it so the planner
            // preferentially branches from the rare outcome's prefix.
            int trueCount = state.trueHits.get();
            int falseCount = state.falseHits.get();
            int total = trueCount + falseCount;
            if (total > 0) {
                int thisCount = condition ? trueCount : falseCount;
                double ratio = (double) thisCount / total;
                // minorityBoost: approaches 10x when ratio->0 (very rare), 1x when ratio=0.5 (balanced)
                double minorityBoost = 1.0 + 9.0 * (1.0 - 2.0 * ratio);
                // Clamp to [1.0, 10.0]: no boost when this is the majority (ratio > 0.5)
                minorityBoost = Math.max(1.0, Math.min(10.0, minorityBoost));
                base *= minorityBoost;
            }
            return base;
        }

        /**
         * Truncates a prefix segment list at the given iteration. Segments ending before
         * the target are kept as-is; the segment containing the target is truncated.
         * Zero-duration segments (where the truncated iteration equals the previous segment's
         * iteration) are dropped to avoid degenerate plans.
         */
        private static List<Segment> truncateAt(List<Segment> segments, long targetIteration) {
            var result = new ArrayList<Segment>();
            for (var segment : segments) {
                if (segment.iteration() <= targetIteration) {
                    result.add(segment);
                } else {
                    // Only add the truncated segment if it would have positive duration
                    long previousIteration =
                            result.isEmpty() ? 0 : result.getLast().iteration();
                    if (targetIteration > previousIteration) {
                        result.add(new Segment(segment.seed(), targetIteration, 0));
                    }
                    break;
                }
            }
            return result;
        }

        private final class GuidanceMonitor implements Predicate<SignalEvent> {
            private final Plan plan;
            /** Hashes captured at each segment boundary, in order of emission. */
            private final List<Integer> segmentHashes = new ArrayList<>();

            GuidanceMonitor(Plan plan) {
                this.plan = plan;
            }

            @Override
            public boolean test(SignalEvent event) {
                // Track segment boundary hashes from lifecycle signals
                if (event.signal() instanceof LifecycleSignal lifecycle
                        && "segment-completed".equals(lifecycle.message())) {
                    segmentHashes.add(lifecycle.hash());
                    return false;
                }

                // Guidance signals: track distance-to-violation for comparative assertions.
                // This is separate from assert handling — guidance never touches prefixes or hit counts.
                if (event.signal() instanceof GuidanceSignal guidanceSignal) {
                    return handleGuidance(guidanceSignal, event.iteration());
                }

                if (!(event.signal() instanceof AssertSignal assertSignal)) {
                    return false;
                }
                return handleAssert(assertSignal, event.iteration());
            }

            /**
             * Handles guidance signals by tracking the minimum distance-to-violation per signal.
             * Returns {@code true} if the distance narrowed (interesting for the planner).
             */
            private boolean handleGuidance(GuidanceSignal guidanceSignal, long iteration) {
                double distance = guidanceSignal.distanceToViolation();
                if (Double.isNaN(distance)) {
                    return false;
                }
                var label = guidanceSignal.message();
                var improved = new AtomicBoolean(false);
                signalMinDistance.compute(label, (_, currentDistance) -> {
                    if (currentDistance == null || distance < currentDistance) {
                        improved.set(currentDistance != null);
                        return distance;
                    }
                    return currentDistance;
                });
                if (improved.get()) {
                    logger.signal("narrowed")
                            .withSignal(label)
                            .withIteration(iteration)
                            .withDistance(distance)
                            .log();
                    return true;
                }
                return false;
            }

            /**
             * Handles assert signals: tracks hit counts per condition, discovers new signals,
             * and saves prefixes for the minority-condition balancing strategy.
             */
            private boolean handleAssert(AssertSignal assertSignal, long iteration) {
                var label = assertSignal.message();
                boolean condition = assertSignal.condition();
                // Ensure per-signal state exists
                var state = signalState.computeIfAbsent(label, _ -> new ConditionPrefixes());

                // Track hit counts per condition
                int totalBefore = state.trueHits.get() + state.falseHits.get();
                if (condition) {
                    state.trueHits.incrementAndGet();
                } else {
                    state.falseHits.incrementAndGet();
                }

                boolean interesting = false;
                if (totalBefore == 0) {
                    logger.signal("found")
                            .withSignal(label)
                            .withIteration(iteration)
                            .log();
                    interesting = true;
                }

                // Save prefix under the appropriate condition slot
                var prefixSegments = buildPrefix(iteration);
                synchronized (state) {
                    var existing = condition ? state.truePrefix : state.falsePrefix;
                    if (existing == null) {
                        // First time seeing this condition outcome
                        if (condition) {
                            state.truePrefix = prefixSegments;
                        } else {
                            state.falsePrefix = prefixSegments;
                        }
                        interesting = true;
                    } else {
                        long existingTotal = existing.getLast().iteration();
                        if (iteration < existingTotal) {
                            // Shorter path to same condition outcome — replace prefix
                            logger.signal("improved")
                                    .withSignal(label + ":" + condition)
                                    .withIteration(iteration)
                                    .withWas(existingTotal)
                                    .log();
                            if (condition) {
                                state.truePrefix = prefixSegments;
                            } else {
                                state.falsePrefix = prefixSegments;
                            }
                            interesting = true;
                        }
                    }
                }
                return interesting;
            }

            /**
             * Builds a prefix from the plan's segments up to the given iteration.
             * Complete segments (those whose boundary was crossed during the run) carry
             * their observed hash from {@code segmentHashes}. The truncated segment at
             * the end gets {@code hash=0} since its boundary was not reached.
             */
            private List<Segment> buildPrefix(long iteration) {
                var prefixSegments = new ArrayList<Segment>();
                var planSegments = plan.segments();
                for (int i = 0; i < planSegments.size(); i++) {
                    var segment = planSegments.get(i);
                    if (segment.iteration() < iteration) {
                        // Complete segment — attach its observed hash if available
                        int hash = i < segmentHashes.size() ? segmentHashes.get(i) : segment.hash();
                        prefixSegments.add(new Segment(segment.seed(), segment.iteration(), hash));
                    } else {
                        // Truncate the segment where the signal was hit (hash unknown at this boundary)
                        prefixSegments.add(new Segment(segment.seed(), iteration));
                        break;
                    }
                }
                return prefixSegments;
            }
        }
    }

    final class ReplayPlanner implements Planner {
        private final Plan plan;

        ReplayPlanner(Plan plan) {
            this.plan = plan;
        }

        @Override
        public ExecutionPlan nextPlan() {
            return new ExecutionPlan(plan, _ -> false);
        }
    }
}
