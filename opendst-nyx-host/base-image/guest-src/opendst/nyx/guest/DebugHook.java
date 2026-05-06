package opendst.nyx.guest;

import java.util.concurrent.Semaphore;

/**
 * Debug hook for nyx-lite debug mode.
 *
 * <p>After each restore-from-snapshot, {@link NyxGuestEntry} calls {@link #awaitDebugger()} when
 * the {@code /opendst-deployment/debug} sentinel file is present. The thread blocks on a semaphore
 * until the user invokes {@link #resume()} from JDB:
 *
 * <pre>
 *   jdb -attach 169.254.0.21:5005
 *   stop at com.example.MyWorkload:42
 *   eval DebugHook.resume()
 *   cont
 * </pre>
 *
 * <p>{@link #resume()} releases the semaphore, unblocking the workload thread with no busy-wait.
 */
public final class DebugHook {

    private static final Semaphore GATE = new Semaphore(0);

    private DebugHook() {}

    /**
     * Releases the gate, allowing the workload to proceed.
     *
     * <p>Invoke from JDB after attaching and setting breakpoints:
     * <pre>eval DebugHook.resume()</pre>
     */
    public static void resume() {
        GATE.release();
    }

    /**
     * Blocks until {@link #resume()} is called.
     *
     * <p>Called by {@link NyxGuestEntry} after each restore-from-snapshot when debug mode
     * is active. Returns immediately once the debugger signals readiness via {@link #resume()}.
     */
    public static void awaitDebugger() throws InterruptedException {
        GATE.acquire();
    }
}
