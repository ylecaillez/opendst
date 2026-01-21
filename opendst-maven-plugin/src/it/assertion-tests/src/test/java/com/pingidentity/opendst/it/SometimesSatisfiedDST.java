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

public class SometimesSatisfiedDST {
    public void run() {
        Signals.ready();
        // In a session with multiple random seeds, some runs will have even salt
        boolean isEven = ThreadLocalRandom.current().nextBoolean();
        Assert.sometimes(isEven, "probabilistic-liveness", null);
    }
}
