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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutionBackend} that drives a persistent nyx-lite shim process.
 *
 * <p>The shim stays alive across iterations: each plan is written as a single JSON line
 * to the shim's stdin, and log lines are read from its stdout until "SHIM_DONE" (emitted
 * after the guest EXECDONE hypercall triggers output flush). The shim internally restores
 * the JVM boot snapshot before each iteration — no JVM restart overhead.
 *
 * <p>The shim is started once via {@link #start(Path, String, OpenDstLogger)} and reused
 * until it exits unexpectedly. After a shim crash, {@link #isHealthy()} returns false
 * and the caller should discard this backend (parallels fork-backend crash detection).
 */
final class NyxBackend implements ExecutionBackend {

    private final Process shim;
    private final BufferedReader shimReader;
    private final TeeBufReader teeReader;
    private final Path snapshotDir;
    private volatile boolean healthy = true;
    private volatile boolean shimDoneSeen = false;

    private NyxBackend(Process shim, Path snapshotDir) {
        this.shim = shim;
        this.snapshotDir = snapshotDir;
        this.shimReader = new BufferedReader(new InputStreamReader(shim.getInputStream()));
        this.teeReader = new TeeBufReader(shimReader);
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
        var snapshotDir = Files.createTempDirectory(Path.of("/home/ylecaillez/tmp"), "opendst-nyx-snaps-");
        var cmd = new ArrayList<String>();
        cmd.add(shimBinary.toString());
        cmd.add("--snapshot-dir");
        cmd.add(snapshotDir.toString());
        cmd.add(vmConfigPath);
        var pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        var shim = pb.start();
        var backend = new NyxBackend(shim, snapshotDir);

        // Drain stdout until "ready\n" — nyx-lite may emit diagnostic lines before it
        String line;
        while ((line = backend.shimReader.readLine()) != null) {
            if ("ready".equals(line)) {
                break;
            }
            logger.raw().debug("shim: " + line);
        }
        if (!"ready".equals(line)) {
            shim.destroyForcibly();
            throw new IllegalStateException("nyx-lite shim did not emit 'ready' (EOF reached)");
        }
        logger.raw().debug("nyx-lite shim ready (boot snapshot taken)");
        return backend;
    }

    @Override
    public void startIteration(Plan plan) throws IOException {
        shimDoneSeen = false;
        teeReader.resetBuffer();
        var json = JSON_MAPPER.writeValueAsString(plan);
        var writer = shim.getOutputStream();
        writer.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.write('\n');
        writer.flush();
    }

    @Override
    public BufferedReader logReader() {
        return teeReader;
    }

    /** Called by TestExecutor when SHIM_DONE is read directly (no drain needed in afterIteration). */
    void markShimDoneSeen() {
        shimDoneSeen = true;
    }

    @Override
    public int awaitCrash() throws InterruptedException {
        healthy = false;
        shim.waitFor();
        return shim.exitValue();
    }

    @Override
    public void afterIteration() throws IOException {
        // If monitorExecutionOutput returned early after "stopped" (before SHIM_DONE arrived),
        // drain stdout until SHIM_DONE so the next iteration sees a clean stream.
        // If it returned after SHIM_DONE itself, nothing to drain.
        if (!shimDoneSeen) {
            String line;
            while (shim.isAlive() && (line = shimReader.readLine()) != null) {
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
        var lines = teeReader.capturedLines();
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        try {
            var tmp = Files.createTempFile("opendst-nyx-", ".log.json");
            tmp.toFile().deleteOnExit();
            Files.write(tmp, lines);
            return Optional.of(tmp);
        } catch (IOException e) {
            return Optional.empty();
        }
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
        // Delete the snapshot directory created for this shim instance.
        if (snapshotDir != null && snapshotDir.toFile().exists()) {
            try (var walk = java.nio.file.Files.walk(snapshotDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (IOException e) {
                // Best-effort cleanup; ignore errors.
            }
        }
    }

    /**
     * Deletes the on-disk snapshot file for the given checkpoint UUID.
     * The shim will treat a subsequent restore attempt for this UUID as a cache miss
     * and fall back to an earlier checkpoint or boot — correct, just slower.
     */
    void evictCheckpoint(String id) {
        if (snapshotDir == null) return;
        var file = snapshotDir.resolve(id + ".snap").toFile();
        file.delete(); // best-effort; ignore result
    }

    /**
     * A {@link BufferedReader} wrapper that tees every line returned by {@link #readLine()}
     * into an in-memory buffer. Call {@link #reset()} at the start of each iteration to
     * discard the previous iteration's lines, and {@link #capturedLines()} to retrieve them.
     */
    private static final class TeeBufReader extends BufferedReader {
        private final BufferedReader delegate;
        private List<String> lines = new ArrayList<>();

        TeeBufReader(BufferedReader delegate) {
            super(delegate, 1); // minimal buffer — actual I/O goes through delegate
            this.delegate = delegate;
        }

        @Override
        public String readLine() throws IOException {
            var line = delegate.readLine();
            if (line != null) {
                lines.add(line);
            }
            return line;
        }

        void resetBuffer() {
            lines = new ArrayList<>();
        }

        List<String> capturedLines() {
            return lines;
        }
    }
}
