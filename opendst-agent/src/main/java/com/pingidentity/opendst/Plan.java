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

import java.util.List;
import java.util.Map;

/**
 * Execution plan for a simulation run.
 *
 * <p>A plan describes the full execution history from iteration 0:
 * <ul>
 *   <li>{@link #checkpoint} — optional fast-forward point; if non-null the execution
 *       backend restores a previously taken snapshot and skips the segments already
 *       covered by it, replaying only the tail.
 *   <li>{@link #segments} — the complete ordered sequence of (seed, until, hash) tuples
 *       from iteration 0. The backend skips segments whose {@code until} is already
 *       covered by the checkpoint.
 *   <li>{@link #faults} — fault injection configuration, applied for the full run.
 *   <li>{@link #hash} — expected run-level state hash; 0 means not yet observed.
 *       Used for determinism verification: a replay that produces a different hash
 *       signals non-determinism.
 * </ul>
 */
public record Plan(Checkpoint checkpoint, List<Segment> segments, Faults.Config faults, int hash) {

    /**
     * Reference to a previously observed execution snapshot.
     *
     * <p>The {@code id} is an opaque UUID string assigned by the execution backend when
     * the snapshot was taken and emitted as a {@code {"type":"checkpoint","id":"..."}}
     * log line. The {@code iteration} is the iteration count at which the snapshot was
     * taken — used by the orchestrator to decide whether the checkpoint is before a
     * branch point. The backend does a direct cache lookup by UUID; {@code iteration}
     * is informational from the backend's perspective.
     *
     * <p>{@code nextBoundary} is the {@code until} value of the segment that was
     * in-progress when the snapshot was taken (= the guest's frozen
     * {@code Source.nextIteration}). The execution backend uses this to compute
     * {@code resume_segment_idx}: skip all segments up to and including the one with
     * {@code until == nextBoundary}, then deliver the following segment at the first
     * boundary hypercall. A value of 0 means unknown (use legacy heuristic).
     */
    public record Checkpoint(String id, long iteration, long nextBoundary) {
        /** Convenience constructor with unknown nextBoundary (legacy / cold-start). */
        public Checkpoint(String id, long iteration) {
            this(id, iteration, 0L);
        }
    }

    /**
     * One segment of a simulation run.
     *
     * <p>A segment spans from the previous segment's {@code until} (exclusive) to this
     * segment's {@code until} (inclusive). At the start of each segment the RNG is
     * re-seeded with {@code seed}. At the end ({@code until} iterations consumed) the
     * state hash is verified against {@code hash} if non-zero.
     *
     * @param seed  RNG seed injected at the start of this segment
     * @param until iteration count at which this segment ends (exclusive boundary);
     *              the last segment typically uses the plan duration
     * @param hash  expected state hash at the segment boundary; 0 = not yet observed
     */
    public record Segment(long seed, long until, int hash) {
        /** Convenience constructor for segments with no expected hash. */
        public Segment(long seed, long until) {
            this(seed, until, 0);
        }
    }

    public Plan {
        segments = segments != null ? List.copyOf(segments) : List.of();
    }

    /** Convenience constructor: no checkpoint, no run-level hash. */
    public Plan(List<Segment> segments, Faults.Config faults) {
        this(null, segments, faults, 0);
    }

    public Plan withHash(int hash) {
        return new Plan(checkpoint, segments, faults, hash);
    }

    /**
     * Returns a copy of this plan with each segment's hash replaced by the corresponding
     * observed hash. Used to enrich plans stored for determinism verification so that
     * replays can validate per-segment state at each boundary.
     */
    public Plan withSegmentHashes(List<Integer> segmentHashes) {
        var updated = new java.util.ArrayList<Segment>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            var seg = segments.get(i);
            var h = i < segmentHashes.size() ? segmentHashes.get(i) : 0;
            updated.add(new Segment(seg.seed(), seg.until(), h));
        }
        return new Plan(checkpoint, updated, faults, hash);
    }

    /**
     * Returns a copy of this plan with each segment's hash replaced by the corresponding
     * observed hash, looked up by the segment's boundary iteration ({@link Segment#until()}).
     *
     * <p>Unlike the list-based overload, this is safe when a checkpoint-assisted run skipped
     * some leading segments: missing entries default to 0 (not yet observed).
     */
    public Plan withSegmentHashes(Map<Long, Integer> segmentHashByIteration) {
        var updated = new java.util.ArrayList<Segment>(segments.size());
        for (var seg : segments) {
            var h = segmentHashByIteration.getOrDefault(seg.until(), 0);
            updated.add(new Segment(seg.seed(), seg.until(), h));
        }
        return new Plan(checkpoint, updated, faults, hash);
    }

    /** Returns a copy of this plan with the given checkpoint reference. */
    public Plan withCheckpoint(Checkpoint checkpoint) {
        return new Plan(checkpoint, segments, faults, hash);
    }
}
