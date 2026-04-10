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
package com.pingidentity.opendst.it.thread;

import com.pingidentity.opendst.sdk.Assert;
import java.util.logging.LogManager;

/**
 * Forces {@link LogManager} initialization inside the simulation context.
 *
 * <p>{@code LogManager} is a JVM singleton. When first initialized it registers a
 * {@code LogManager$Cleaner} shutdown hook — a platform thread. If this initialization happens
 * inside a simulation, the hook goes through the intercepted {@code Runtime.addShutdownHook()} and
 * the agent must skip it to preserve determinism.
 */
public final class ShutdownHookApp {
    public static void main(String[] args) {
        // Force LogManager initialization inside the simulation context.
        // This triggers registration of LogManager$Cleaner (a platform thread shutdown hook)
        // through the intercepted Runtime.addShutdownHook() path.
        LogManager.getLogManager();

        System.out.println("Shutdown hook test completed");
        Assert.reachable("shutdown-hook-completed", null);
    }
}
