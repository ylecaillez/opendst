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

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

/**
 * An application containing an {@code always} assertion in dead code.
 * The assertion is discovered by offline instrumentation but never hit at runtime.
 */
public final class AlwaysNeverHitApp {
    public static void main(String[] args) {
        Signals.ready();
        // This method is called, but doesn't contain the assertion.
        // The assertion is in 'unreachableMethod' below.
    }

    private static void unreachableMethod() {
        // This will be discovered by offline instrumentation,
        // but never hit during the simulation.
        Assert.always(true, "dead-code-assertion", null);
    }
}
