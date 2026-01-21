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
package com.pingidentity.opendst.it;

import java.util.concurrent.ThreadLocalRandom;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

/**
 * Tests that comparative assertions emit guidance data (left/right values)
 * that the orchestrator can use for distance-guided exploration.
 *
 * <p>The random value will vary across simulation runs. The orchestrator should
 * track the minimum distance-to-violation and prefer plans that narrow the gap.
 * The assertion always passes (value is always >= 0 and we assert > -1),
 * so this DST should succeed.
 */
public class ComparativeAssertionDST {
    public void run() {
        Signals.ready();
        int value = ThreadLocalRandom.current().nextInt(100);
        Assert.alwaysGreaterThan(value, -1, "comparative-safety", null);
    }
}
