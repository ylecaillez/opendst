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

import java.util.Set;

/**
 * Run-loop control parameters for the parent-side simulation driver.
 *
 * @param replayProbability  chance of replaying a cached past plan instead of asking the
 *     {@link Orchestrator} for a new one (forced to {@code 0} in replay/debug mode)
 * @param isDebugOrReplay    when {@code true}, run a single fork once and skip cleanup
 * @param stagnationLimit    stop a fork after this many consecutive non-interesting runs
 * @param forkCount          number of concurrent child JVMs to run
 * @param stopConditions     early-stopping flags from {@code --stop} (combinable, may be empty)
 */
record RunConfig(
        double replayProbability,
        boolean isDebugOrReplay,
        int stagnationLimit,
        int forkCount,
        Set<StopCondition> stopConditions) {}
