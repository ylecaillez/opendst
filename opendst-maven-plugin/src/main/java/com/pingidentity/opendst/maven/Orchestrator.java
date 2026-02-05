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

import static java.lang.Runtime.getRuntime;

import com.pingidentity.opendst.maven.ContinuousTestMojo.LogStatement;
import java.util.List;

interface Orchestrator {
    record Plan(String rid, List<Segment> segments) {
        record Segment(long seed, long iteration) {}

        public Plan {
            Segment last = null;
            for (var segment : segments) {
                if (last != null && last.iteration() > segment.iteration()) {
                    System.err.println("The plan is invalid: at least one segment is out of order");
                    getRuntime().halt(1);
                }
                last = segment;
            }
            segments = List.copyOf(segments);
        }
    }

    Plan nextPlan();

    void onLogReceived(Plan plan, LogStatement logLine);

    void onPlanTerminated(Plan plan, int code, LogStatement lastLog);
}
