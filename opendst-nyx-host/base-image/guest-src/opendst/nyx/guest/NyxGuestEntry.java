package opendst.nyx.guest;

import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guest-side entry point for the nyx-lite VM.
 *
 * <p>Loads OpenDSTExecutor from the deployment JARs and delegates to it,
 * but wraps stdin/stdout to use shared memory instead of pipes.
 *
 * <p>After classloader and deployment setup, takes an initial snapshot before reading the
 * plan. Each iteration restores to that snapshot, reads the plan from shared memory, runs
 * one simulation via OpenDSTExecutor, and signals done via {@link SharedOutputStream}
 * when it detects the "stopped" lifecycle signal.
 */
public final class NyxGuestEntry {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: NyxGuestEntry <deploymentDir>");
            System.exit(1);
        }

        var deploymentDir = Path.of(args[0]);

        // --- Step 1: allocate + register shared memory regions with the host ---
        var shm = new SharedMemory();

        // --- Step 2: build a classloader from all system/*.jar ---
        var systemDir = deploymentDir.resolve("system");
        var systemUrls = Files.list(systemDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .sorted()
                .map(p -> {
                    try { return p.toUri().toURL(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toArray(URL[]::new);
        // Parent must be the system classloader (not just platform) so that
        // NyxSegmentHypercall (loaded via systemLoader) can resolve Hypercall
        // from nyx-guest.jar, which is on the system classpath (-cp).
        var systemLoader = new URLClassLoader("system-loader", systemUrls,
                ClassLoader.getSystemClassLoader());

        // Warm up: load OpenDSTExecutor so its static initialisation + Jackson
        // class loading all happen before we take the snapshot.
        var executorClass = Class.forName(
                "com.pingidentity.opendst.runner.OpenDSTExecutor", true, systemLoader);
        var mainMethod = executorClass.getMethod("main", String[].class);

        // --- Step 3: wire up RandomInterceptors fields ---
        // Must use systemLoader so we reach the same instance that Source uses.
        var randomInterceptorsClass = Class.forName(
                "com.pingidentity.opendst.RandomInterceptors", true, systemLoader);

        // Segment supplier: called by Source.next() at each boundary
        // to read the next (seed, until) pair from shared memory.
        var nyxSupplierField = randomInterceptorsClass.getDeclaredField("NYX_SEGMENT_SUPPLIER");
        nyxSupplierField.setAccessible(true);
        var segmentClass = Class.forName("com.pingidentity.opendst.Plan$Segment", true, systemLoader);
        var segmentCtor = segmentClass.getDeclaredConstructor(long.class, long.class);
        nyxSupplierField.set(null, (java.util.function.Supplier<Object>) () -> {
            var next = shm.readNextSegment();
            if (next == null) return null;
            try {
                return segmentCtor.newInstance(next[0], next[1]);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("failed to create Segment", e);
            }
        });

        // Snapshot callback: called by Scheduler.run() between tasks (CURRENT_NODE == null)
        // to issue periodic snapshots.
        var nyxSnapshotCallbackField =
                randomInterceptorsClass.getDeclaredField("NYX_SNAPSHOT_CALLBACK");
        nyxSnapshotCallbackField.setAccessible(true);
        nyxSnapshotCallbackField.set(null, (Runnable) Hypercall::snapshot);

        // Segment boundary callback: called by Source.step() at each segment boundary
        // (also in Scheduler context, CURRENT_NODE == null) to signal the shim to write
        // the next segment to INPUT.
        var nyxSegmentBoundaryCallbackField =
                randomInterceptorsClass.getDeclaredField("NYX_SEGMENT_BOUNDARY_CALLBACK");
        nyxSegmentBoundaryCallbackField.setAccessible(true);
        nyxSegmentBoundaryCallbackField.set(null, (Runnable) Hypercall::segmentBoundary);

        // Take the snapshot so boot_to_snapshot() on the host side can proceed.
        // This snapshot captures class loading, Jackson init, and reflection setup.
        // Each restore re-runs readPlan() + OpenDSTExecutor.main() from here,
        // ensuring the plan (including fault config) is always fresh.
        Hypercall.snapshot();
        // Execution resumes here after every restore.

        // Capture raw err before the agent can intercept System.err.
        var rawErr = System.err;

        // --- Step 4: read plan from shared memory, inject as System.in ---
        shm.resetOutput();
        var planJson = shm.readPlan();
        System.setIn(new ByteArrayInputStream(
                planJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // --- Step 5: redirect System.out to the shared output buffer ---
        var originalOut = System.out;
        System.setOut(new PrintStream(new SharedOutputStream(shm), true, "UTF-8"));

        // --- Step 6: run one simulation via OpenDSTExecutor ---
        try {
            mainMethod.invoke(null, (Object) new String[]{deploymentDir.toString()});
        } catch (Throwable t) {
            rawErr.println("[NyxGuestEntry] OpenDSTExecutor.main threw: " + t);
            t.printStackTrace(rawErr);
            // Also emit a structured signal to the shared output buffer so the runner
            // can see the exception in the report even if rawErr is not captured.
            // System.out is still the SharedOutputStream at this point.
            var cause = t.toString().replace("\\", "\\\\").replace("\"", "\\\"");
            System.out.println("{\"it\":0,\"source\":\"guest\",\"log\":"
                    + "{\"signal\":\"lifecycle\",\"message\":\"internal error\","
                    + "\"cause\":\"jvm-exception: " + cause + "\"}}");
            System.out.flush();
        } finally {
            // Emit a jvm-exiting marker BEFORE restoring System.out.
            // Absence of this line in the output means the JVM died before the finally
            // block ran (native crash, OOM kill, etc.) rather than a Java-level exit.
            System.out.println("{\"it\":0,\"source\":\"guest\",\"log\":"
                    + "{\"signal\":\"lifecycle\",\"message\":\"jvm-exit\"}}");
            System.out.flush();
            System.setOut(originalOut);
            nyxSupplierField.set(null, null);
            nyxSnapshotCallbackField.set(null, null);
            nyxSegmentBoundaryCallbackField.set(null, null);
        }
    }
}
