/*
 * Copyright 2024-2026 Ping Identity Corporation
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
package com.pingidentity.opendst.it.flakytest;

import static java.lang.System.out;

import com.pingidentity.opendst.TimeInterceptors.RealTime;

/**
 * A DST scenario that intentionally introduces non-determinism to verify the
 * simulator's ability to detect it.
 * <p>
 * {@link RealTime#currentTimeMillis()} bypasses the simulator's virtual clock
 * and returns the real system time, which differs across simulation runs.
 * This causes the execution hash to change on replay, which the framework
 * must detect and report as a non-determinism property violation.
 */
public class FlakyDST {
    public static void main(String[] args) {
        out.printf("Intentional non-determinism: %d%n", RealTime.currentTimeMillis());
    }
}
