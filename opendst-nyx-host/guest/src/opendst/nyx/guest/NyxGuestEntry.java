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
 * <p>After classloader and deployment setup, calls {@link Hypercall#snapshot()}
 * so the host captures the warmed-up JVM state. Each iteration restores to
 * that snapshot, reads the plan from shared memory, runs one simulation via
 * OpenDSTExecutor, and signals done via {@link SharedOutputStream} when it
 * detects the "stopped" lifecycle signal.
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
        var systemLoader = new URLClassLoader("system-loader", systemUrls,
                ClassLoader.getPlatformClassLoader());

        // Warm up: load OpenDSTExecutor so its static initialisation + Jackson
        // class loading all happen before we take the snapshot.
        var executorClass = Class.forName(
                "com.pingidentity.opendst.runner.OpenDSTExecutor", true, systemLoader);
        var mainMethod = executorClass.getMethod("main", String[].class);

        // --- Step 3: snapshot — host captures VM state here ---
        Hypercall.snapshot();

        // Execution resumes here after every snapshot restore.

        // Capture raw err before the agent can intercept System.err.
        var rawErr = System.err;

        // --- Step 4: read plan from shared memory, inject as System.in ---
        shm.resetOutput();
        var planJson = shm.readPlan();
        System.setIn(new ByteArrayInputStream(
                planJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // --- Step 5: redirect System.out to the shared output buffer ---
        // SharedOutputStream detects the "stopped" signal and calls finishIteration itself.
        var originalOut = System.out;
        System.setOut(new PrintStream(new SharedOutputStream(shm, rawErr), true, "UTF-8"));

        // --- Step 6: run one simulation via OpenDSTExecutor ---
        // The host shim detects "stopped" in the output stream and ends the iteration
        // from its side (restoring the snapshot), so this call never returns normally.
        try {
            mainMethod.invoke(null, (Object) new String[]{deploymentDir.toString()});
        } catch (Throwable t) {
            rawErr.println("[NyxGuestEntry] OpenDSTExecutor.main threw: " + t);
            t.printStackTrace(rawErr);
        } finally {
            System.setOut(originalOut);
        }
    }
}
