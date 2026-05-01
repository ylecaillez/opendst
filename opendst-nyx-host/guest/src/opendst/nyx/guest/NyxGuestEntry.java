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
 * <p>After classloader and deployment setup, sets {@code NYX_BOOT_SNAPSHOT_PENDING} so
 * that the first {@code Source.next()} call issues the deferred boot snapshot hypercall.
 * This captures plan parsing, Simulator construction, and node bootstrapping in the
 * snapshot — not just class loading. Each iteration restores to that snapshot, reads the
 * plan from shared memory, runs one simulation via OpenDSTExecutor, and signals done via
 * {@link SharedOutputStream} when it detects the "stopped" lifecycle signal.
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

        // Segment supplier: called by Source.next() at each boundary and at boot snapshot
        // to read the next (seed, iteration) pair from shared memory.
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

        // Boot snapshot flag: Source.next() issues the deferred boot snapshot hypercall
        // on the very first draw, then re-seeds from SHM. Setting this here (before any
        // simulation code runs) means the snapshot captures everything up to that point.
        var bootSnapshotField = randomInterceptorsClass.getDeclaredField("NYX_BOOT_SNAPSHOT_PENDING");
        bootSnapshotField.setAccessible(true);
        bootSnapshotField.set(null, true);

        // Take the initial snapshot so boot_to_snapshot() on the host side can proceed.
        // This snapshot captures class loading and field setup. The deferred BOOT_SNAPSHOT
        // hypercall (issued by Source.next() on the first draw) will overwrite it with a
        // deeper snapshot that also captures plan parsing and node bootstrapping.
        Hypercall.snapshot();
        // Execution resumes here after every restore from the initial snapshot.
        // Source.next() will immediately re-snapshot (deferred boot) on the first draw.

        // Capture raw err before the agent can intercept System.err.
        var rawErr = System.err;

        // --- Step 4: read plan from shared memory, inject as System.in ---
        shm.resetOutput();
        var planJson = shm.readPlan();
        System.setIn(new ByteArrayInputStream(
                planJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // --- Step 5: redirect System.out to the shared output buffer ---
        var originalOut = System.out;
        System.setOut(new PrintStream(new SharedOutputStream(shm, rawErr), true, "UTF-8"));

        // --- Step 6: run one simulation via OpenDSTExecutor ---
        try {
            mainMethod.invoke(null, (Object) new String[]{deploymentDir.toString()});
        } catch (Throwable t) {
            rawErr.println("[NyxGuestEntry] OpenDSTExecutor.main threw: " + t);
            t.printStackTrace(rawErr);
        } finally {
            System.setOut(originalOut);
            nyxSupplierField.set(null, null);
            bootSnapshotField.set(null, false);
        }
    }
}
