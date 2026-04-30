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

import com.pingidentity.opendst.Plan;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Abstraction over a single simulation execution slot.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link TestExecutor.ForkBackend} — spawns a fresh child JVM per iteration (current default)</li>
 *   <li>{@link NyxBackend} — sends plans to a persistent nyx-lite shim process over stdin/stdout</li>
 * </ul>
 *
 * <p>Lifecycle per iteration:
 * <ol>
 *   <li>{@link #startIteration(Plan)} — submit the plan</li>
 *   <li>Consume {@link #logReader()} until "stopped" signal or EOF</li>
 *   <li>On EOF (crash): call {@link #awaitCrash()} for exit code</li>
 *   <li>{@link #afterIteration()} — per-iteration cleanup</li>
 *   <li>{@link #cleanupAfterRun()} — delete temp dirs (no-op for nyx)</li>
 * </ol>
 */
interface ExecutionBackend extends Closeable {

    /** Submit the plan and start executing. */
    void startIteration(Plan plan) throws IOException;

    /** Reader from which log lines for the current iteration are available. */
    BufferedReader logReader();

    /**
     * Called when the log stream closes unexpectedly (process crash).
     * Returns the exit code of the terminated process/shim.
     */
    int awaitCrash() throws InterruptedException;

    /**
     * Per-iteration cleanup. For fork backend: kill process, remove shutdown hook.
     * For nyx backend: no-op (shim stays alive).
     */
    void afterIteration() throws IOException, InterruptedException;

    /**
     * Whether this backend is healthy and can be used for the next iteration.
     * Fork backend: always true (each iteration spawns fresh).
     * Nyx backend: false if the shim process died.
     */
    boolean isHealthy();

    /** Path to the simulator log file for this run, if available. Empty for nyx. */
    Optional<Path> simulatorLogPath();

    /** Delete any temporary directories created for this run (run dirs for fork; no-op for nyx). */
    void cleanupAfterRun() throws IOException;
}
