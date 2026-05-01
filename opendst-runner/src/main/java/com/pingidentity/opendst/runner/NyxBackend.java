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

import static com.pingidentity.opendst.runner.Commons.JSON_MAPPER;

import com.pingidentity.opendst.Plan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutionBackend} that drives a persistent nyx-lite shim process.
 *
 * <p>The shim stays alive across iterations: each plan is written as a single JSON line
 * to the shim's stdin, and log lines are read from its stdout until "stopped" (signalled
 * by the guest EXECDONE hypercall). The shim internally restores the JVM boot snapshot
 * before each iteration — no JVM restart overhead.
 *
 * <p>The shim is started once via {@link #start(Path, String, OpenDstLogger)} and reused
 * until it exits unexpectedly. After a shim crash, {@link #isHealthy()} returns false
 * and the caller should discard this backend (parallels fork-backend crash detection).
 */
final class NyxBackend implements ExecutionBackend {

    private final Process shim;
    private final BufferedReader reader;
    private volatile boolean healthy = true;
    private volatile boolean iterationComplete = false;

    private NyxBackend(Process shim) {
        this.shim = shim;
        this.reader = new BufferedReader(new InputStreamReader(shim.getInputStream()));
    }

    /**
     * Starts the nyx-lite shim and waits for it to emit {@code "ready\n"}, indicating
     * the JVM guest has booted and the base snapshot has been taken.
     *
     * @param shimBinary path to the {@code opendst-nyx-shim} binary
     * @param vmConfigPath path to the nyx-lite {@code vmconfig.json}
     * @param logger logger for diagnostic output
     * @throws IOException if the shim cannot be started
     * @throws IllegalStateException if the shim exits before emitting "ready"
     */
    static NyxBackend start(Path shimBinary, String vmConfigPath, OpenDstLogger logger)
            throws IOException, InterruptedException {
        var pb = new ProcessBuilder(List.of(shimBinary.toString(), vmConfigPath)).redirectErrorStream(false);
        // Shim writes diagnostic output to stderr — let it flow to our stderr
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        var shim = pb.start();
        var backend = new NyxBackend(shim);

        // Drain stdout until "ready\n" — nyx-lite may emit diagnostic lines before it
        String line;
        while ((line = backend.reader.readLine()) != null) {
            if ("ready".equals(line)) {
                break;
            }
            logger.raw().debug("shim: " + line);
        }
        if (!"ready".equals(line)) {
            shim.destroyForcibly();
            throw new IllegalStateException("nyx-lite shim did not emit 'ready' (EOF reached)");
        }
        logger.raw().info("nyx-lite shim ready (boot snapshot taken)");
        return backend;
    }

    @Override
    public void startIteration(Plan plan) throws IOException {
        iterationComplete = false;
        var json = JSON_MAPPER.writeValueAsString(plan);
        var writer = shim.getOutputStream();
        writer.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.write('\n');
        writer.flush();
    }

    @Override
    public BufferedReader logReader() {
        return reader;
    }

    /** Called by ExecutionResult when it processes the "stopped" lifecycle signal. */
    void markIterationComplete() {
        iterationComplete = true;
    }

    @Override
    public int awaitCrash() throws InterruptedException {
        healthy = false;
        shim.waitFor();
        return shim.exitValue();
    }

    @Override
    public void afterIteration() throws IOException {
        // If monitorExecutionOutput returned early (interesting signal before SHIM_DONE),
        // drain stdout until SHIM_DONE so the next iteration sees a clean stream.
        if (!iterationComplete) {
            String line;
            while (shim.isAlive() && (line = reader.readLine()) != null) {
                if ("SHIM_DONE".equals(line)) {
                    break;
                }
            }
        }
    }

    @Override
    public boolean isHealthy() {
        if (!healthy) {
            return false;
        }
        // Detect unexpected shim exit
        if (!shim.isAlive()) {
            healthy = false;
            return false;
        }
        return true;
    }

    @Override
    public Optional<Path> simulatorLogPath() {
        // Nyx shim doesn't write a simulator.log file (output goes directly to stdout)
        return Optional.empty();
    }

    @Override
    public void cleanupAfterRun() {
        // No temporary run directories to clean up
    }

    @Override
    public void close() throws IOException {
        shim.destroy();
        try {
            if (!shim.waitFor(10, TimeUnit.SECONDS)) {
                shim.destroyForcibly();
            }
        } catch (InterruptedException e) {
            shim.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
