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

import picocli.CommandLine;

/**
 * Early-stopping conditions for the run loop, settable via repeated {@code --stop} CLI flags.
 *
 * <ul>
 *   <li>{@link #ANY_FAIL} — stop immediately on the first assertion failure</li>
 *   <li>{@link #ALL_PASS} — stop early once all assertions are passing,
 *       after at least {@code --stagnation-limit} executions for confidence</li>
 * </ul>
 *
 * <p>When no {@code --stop} flag is given, the runner runs until the stagnation
 * limit is reached (no early stopping).
 */
public enum StopCondition {
    ANY_FAIL,
    ALL_PASS;

    /** Picocli converter that accepts CLI values like {@code any-fail} or {@code ANY_FAIL}. */
    static final class Converter implements CommandLine.ITypeConverter<StopCondition> {
        @Override
        public StopCondition convert(String value) {
            return StopCondition.valueOf(value.toUpperCase().replace('-', '_'));
        }
    }
}
