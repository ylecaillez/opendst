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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutionBackend} that drives a long-lived nyx-lite shim process.
 *
 * <p>A single shim process is spawned in {@link #start} and reused across all iterations.
 * The shim boots the guest VM once (or restores from the on-disk boot snapshot) and keeps
 * the VM alive in memory. Between iterations the shim calls {@code apply_snapshot} to reset
 * KVM dirty pages to the boot state — no process restart, no disk I/O.
 *
 * <p>The snapshot directory persists for the lifetime of this backend. Incremental
 * checkpoints are written to disk by the shim and loaded on demand; only the boot snapshot
 * stays in RAM inside the shim process.
 *
 * <p>Protocol:
 * <ul>
 *   <li>Shim emits {@code ready\n} once after boot/restore, then loops.
 *   <li>Each iteration: Java writes one plan JSON line; shim executes and emits {@code SHIM_DONE\n}.
 *   <li>Java closes stdin to signal EOF; shim exits cleanly.
 * </ul>
 */
final class NyxBackend implements ExecutionBackend {

    private final Path shimBinary;
    private final String vmConfigPath;
    private final Path snapshotDir;
    private final OpenDstLogger logger;

    private Process shim;
    private OutputStream shimStdin;
    private final TeeBufReader teeReader;

    private NyxBackend(Path shimBinary, String vmConfigPath, Path snapshotDir, OpenDstLogger logger) {
        this.shimBinary = shimBinary;
        this.vmConfigPath = vmConfigPath;
        this.snapshotDir = snapshotDir;
        this.logger = logger;
        this.teeReader = new TeeBufReader();
    }

    /**
     * Creates a NyxBackend, spawns the shim process, and waits for the initial {@code ready}.
     * The shim boots the guest VM (or restores from disk) exactly once here.
     */
    static NyxBackend start(Path shimBinary, String vmConfigPath, OpenDstLogger logger)
            throws IOException {
        var snapshotDir = Files.createTempDirectory(Path.of("/home/ylecaillez/tmp"), "opendst-nyx-snaps-");
        var backend = new NyxBackend(shimBinary, vmConfigPath, snapshotDir, logger);
        backend.spawnAndAwaitReady();
        return backend;
    }

    private void spawnAndAwaitReady() throws IOException {
        long t0 = System.nanoTime();
        var cmd = List.of(
                shimBinary.toString(),
                "--snapshot-dir", snapshotDir.toString(),
                vmConfigPath);

        var pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        shim = pb.start();
        shimStdin = shim.getOutputStream();

        var rawReader = new BufferedReader(new InputStreamReader(shim.getInputStream()));
        teeReader.setDelegate(rawReader);

        // Wait for "ready" — the shim boots (or disk-restores) once here.
        String line;
        while ((line = rawReader.readLine()) != null) {
            if ("ready".equals(line)) {
                break;
            }
            logger.raw().debug("shim: " + line);
        }
        if (!"ready".equals(line)) {
            shim.destroyForcibly();
            throw new IllegalStateException("nyx-lite shim did not emit 'ready' (EOF reached)");
        }
        long readyMs = (System.nanoTime() - t0) / 1_000_000;
        logger.raw().debug("shim ready in " + readyMs + "ms");
    }

    @Override
    public void startIteration(Plan plan) throws IOException {
        // Reset the per-iteration line buffer; the underlying stream stays open.
        teeReader.resetLines();

        // Send the plan to the already-running shim.
        var json = JSON_MAPPER.writeValueAsString(plan);
        shimStdin.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        shimStdin.write('\n');
        shimStdin.flush();
    }

    @Override
    public BufferedReader logReader() {
        return teeReader;
    }

    /** No-op: SHIM_DONE is consumed by monitorExecutionOutput in TestExecutor. */
    void markShimDoneSeen() {}

    @Override
    public int awaitCrash() throws InterruptedException {
        shim.waitFor();
        return shim.exitValue();
    }

    @Override
    public void afterIteration() throws IOException, InterruptedException {
        // Nothing to do: shim stays alive, ready for the next plan.
    }

    @Override
    public boolean isHealthy() {
        return shim != null && shim.isAlive();
    }

    @Override
    public Optional<Path> simulatorLogPath() {
        var lines = teeReader.capturedLines();
        if (lines.isEmpty()) return Optional.empty();
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
        // No per-iteration temp dirs — snapshot dir persists for the slot lifetime.
    }

    @Override
    public void close() throws IOException {
        // Signal EOF to the shim; it will exit its read loop cleanly.
        if (shimStdin != null) {
            try { shimStdin.close(); } catch (IOException ignored) {}
        }
        if (shim != null && shim.isAlive()) {
            try {
                if (!shim.waitFor(5, TimeUnit.SECONDS)) {
                    shim.destroyForcibly();
                }
            } catch (InterruptedException e) {
                shim.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (snapshotDir != null && snapshotDir.toFile().exists()) {
            try (var walk = Files.walk(snapshotDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (IOException e) {
                // Best-effort cleanup.
            }
        }
    }

    /**
     * A {@link BufferedReader} wrapper that tees every line into an in-memory buffer per iteration.
     * The underlying delegate stream is set once and never replaced; only the line buffer is cleared
     * between iterations.
     */
    private static final class TeeBufReader extends BufferedReader {
        private BufferedReader delegate;
        private List<String> lines = new ArrayList<>();

        TeeBufReader() {
            super(new java.io.StringReader(""), 1); // placeholder until setDelegate is called
        }

        void setDelegate(BufferedReader newDelegate) {
            this.delegate = newDelegate;
            this.lines = new ArrayList<>();
        }

        void resetLines() {
            this.lines = new ArrayList<>();
        }

        @Override
        public String readLine() throws IOException {
            var line = delegate.readLine();
            if (line != null) lines.add(line);
            return line;
        }

        List<String> capturedLines() {
            return lines;
        }
    }
}
