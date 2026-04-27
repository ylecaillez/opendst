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
package com.pingidentity.opendst.common;

import java.util.List;

/**
 * Execution plan for a simulation run.
 */
public record Plan(List<Segment> segments, Faults.Config faults, int hash) {
    public record Segment(long seed, long iteration, int hash) {
        /** Convenience constructor for segments with no expected hash. */
        public Segment(long seed, long iteration) {
            this(seed, iteration, 0);
        }
    }

    public Plan {
        segments = segments != null ? List.copyOf(segments) : List.of();
    }

    public Plan(List<Segment> segments, Faults.Config faults) {
        this(segments, faults, 0);
    }

    public Plan withHash(int hash) {
        return new Plan(segments, faults, hash);
    }
}
