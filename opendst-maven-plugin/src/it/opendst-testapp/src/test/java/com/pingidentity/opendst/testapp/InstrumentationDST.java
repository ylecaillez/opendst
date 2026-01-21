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
package com.pingidentity.opendst.testapp;

/**
 * Verifies that the simulator agent is correctly instrumenting classes.
 */
public class InstrumentationDST {
    public void run() {
        if (new Thread(() -> {}).isVirtual()) {
            throw new AssertionError("OpenDST instrumentation");
        }
        throw new IllegalStateException("Instrumentation failed: Thread is not virtual");
    }
}
