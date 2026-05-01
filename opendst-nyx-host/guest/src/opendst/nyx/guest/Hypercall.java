package opendst.nyx.guest;

/**
 * JNI wrapper around the nyx-lite hypercall ABI.
 *
 * <p>The native side issues {@code int 3} with registers set per the nyx-lite guest
 * protocol (see examples/test_guest_runner.rs in the nyx-lite repo):
 * <pre>
 *   RAX = NYX_LITE  (0x6574696c2d78796e)
 *   R8  = hypercall_num
 *   R9  = arg1, R10 = arg2, R11 = arg3, R12 = arg4
 * </pre>
 */
public final class Hypercall {

    // Hypercall opcodes — must match examples/test_guest_runner.rs in nyx-lite.
    static final long EXECDONE     = 0x656e6f6463657865L;
    static final long SNAPSHOT     = 0x746f687370616e73L;
    static final long BOOT_SNAPSHOT = 0x706e73746f6f6273L; // "sbootsnp" — deferred boot snapshot
    static final long SHAREMEM     = 0x6d656d6572616873L;
    static final long DBGPRINT     = 0x746e697270676264L;
    static final long FAILTEST     = 0x747365746c696166L;

    static {
        System.loadLibrary("hypercall");
    }

    private Hypercall() {}

    /**
     * Raw hypercall: passes {@code num, a1, a2, a3, a4} via the nyx-lite int3 ABI.
     * String args (name, msg) are passed as null-terminated C strings; the native
     * implementation handles the Java→native string conversion.
     */
    static native void callWithName(long num, String name, long a2, long a3, long a4);

    static native void call(long num, long a1, long a2, long a3, long a4);

    /** Registers a shared memory region with the host. {@code buf} must be a direct ByteBuffer. */
    static void registerRegion(String name, java.nio.ByteBuffer buf) {
        if (!buf.isDirect()) throw new IllegalArgumentException("buf must be a direct ByteBuffer");
        callWithName(SHAREMEM, name, directAddress(buf), buf.capacity(), 0L);
    }

    /** Tells the host to take the base snapshot. Execution resumes here on every restore. */
    static void snapshot() {
        call(SNAPSHOT, 0L, 0L, 0L, 0L);
    }

    /**
     * Deferred boot snapshot: issued at the first random draw instead of at startup.
     * The host takes the boot snapshot and writes the first segment to INPUT before resuming.
     * This allows all JVM warm-up (class loading, plan parsing, node init) to be captured.
     */
    static void bootSnapshot() {
        call(BOOT_SNAPSHOT, 0L, 0L, 0L, 0L);
    }

    /** Signals the host that the current iteration is complete. */
    static void done(long exitCode) {
        call(EXECDONE, exitCode, 0L, 0L, 0L);
    }

    static void dbgPrint(String msg) {
        callWithName(DBGPRINT, msg, 0L, 0L, 0L);
    }

    /** Returns the native address of a direct ByteBuffer. */
    private static native long directAddress(java.nio.ByteBuffer buf);
}
