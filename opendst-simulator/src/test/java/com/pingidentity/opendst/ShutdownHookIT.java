/*
 * Copyright 2025 Ping Identity Corporation
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

import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.lang.Runtime.getRuntime;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

public class ShutdownHookIT {
    @Test
    public void addShutdownHook() throws Exception {
        var shutdown = new AtomicBoolean();
        runSimulation(() -> {
            getRuntime().addShutdownHook(new Thread(() -> shutdown.set(true)));
            return null;
        });
        assertThat(shutdown.get()).isTrue();
    }

    @Test
    public void removeShutdownHook() throws Exception {
        var shutdownExecuted = new AtomicBoolean();
        var shutdownUnregistered = new AtomicBoolean();

        runSimulation(() -> {
            var hook = new Thread(() -> shutdownExecuted.set(true));
            getRuntime().addShutdownHook(hook);
            shutdownUnregistered.set(getRuntime().removeShutdownHook(hook));
            return null;
        });
        assertThat(shutdownExecuted.get()).isFalse();
        assertThat(shutdownUnregistered.get()).isTrue();
    }
}
