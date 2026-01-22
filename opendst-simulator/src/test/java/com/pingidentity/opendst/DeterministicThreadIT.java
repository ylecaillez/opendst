/*
 * Copyright 2025-2026 Ping Identity Corporation
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

import static com.diffplug.selfie.Selfie.expectSelfie;
import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.lang.Thread.ofPlatform;
import static java.lang.Thread.ofVirtual;
import static java.lang.Thread.startVirtualThread;
import static java.util.concurrent.Executors.defaultThreadFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public class DeterministicThreadIT {
    @Test
    public void deterministicThreadId() throws Exception {
        var writer = new ObjectMapper().writerWithDefaultPrettyPrinter();
        var ref = new AtomicReference<String>();
        runSimulation(() -> {
            ref.set(writer.writeValueAsString(Map.of(
                    "ofVirtual.unstarted", ofVirtual().unstarted(() -> {}),
                    "ofVirtual.factory", ofVirtual().factory().newThread(() -> {}),
                    "startVirtualThread", startVirtualThread(() -> {}),
                    "new Thread", new Thread().threadId(),
                    "ofPlatform.unstarted", ofPlatform().unstarted(() -> {}),
                    "ofPlatform.factory", ofPlatform().factory().newThread(() -> {}),
                    "defaultThreadFactory", defaultThreadFactory().newThread(() -> {}))));
            return null;
        });
        expectSelfie(ref.get()).toMatchDisk();
    }
}
