package opendst.nyx.guest;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

/**
 * Panama FFM wrapper around the nyx-lite hypercall ABI.
 *
 * <p>Loads {@code libhypercall.so} via {@link System#loadLibrary} and binds
 * a single exported C function:
 * <pre>
 *   void hypercall(uint64_t num, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4)
 * </pre>
 * which issues {@code int 3} with registers set per the nyx-lite guest protocol:
 * <pre>
 *   RAX = NYX_LITE  (0x6574696c2d78796e)
 *   R8  = num
 *   R9  = a1, R10 = a2, R11 = a3, R12 = a4
 * </pre>
 *
 * <p>No JNI glue is needed: {@code directAddress} is implemented in pure Java
 * via {@link MemorySegment#ofBuffer}, and C strings for {@link #callWithName}
 * are allocated with a confined {@link Arena}.
 */
public final class Hypercall {

    static final long EXECDONE        = 0x656e6f6463657865L;
    static final long SNAPSHOT        = 0x746f687370616e73L;
    static final long BOOT_SNAPSHOT   = 0x706e73746f6f6273L;
    static final long SHAREMEM        = 0x6d656d6572616873L;
    static final long DBGPRINT        = 0x746e697270676264L;
    static final long FAILTEST        = 0x747365746c696166L;
    /** "segbndry" LE — signals the shim to write the next segment to INPUT. */
    static final long SEGMENT_BOUNDARY = 0x7972646e62676573L;

    private static final MethodHandle HYPERCALL;

    static {
        System.loadLibrary("hypercall");
        var linker   = Linker.nativeLinker();
        var lookup   = SymbolLookup.loaderLookup();
        var sym      = lookup.find("hypercall")
                .orElseThrow(() -> new LinkageError("hypercall symbol not found in libhypercall.so"));
        var desc     = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
        HYPERCALL = linker.downcallHandle(sym, desc);
    }

    private Hypercall() {}

    static void call(long num, long a1, long a2, long a3, long a4) {
        try {
            HYPERCALL.invokeExact(num, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw new RuntimeException("hypercall failed", t);
        }
    }

    /** Passes {@code name} as a null-terminated C string (arg1). */
    static void callWithName(long num, String name, long a2, long a3, long a4) {
        try (var arena = Arena.ofConfined()) {
            var cstr = arena.allocateFrom(name);
            call(num, cstr.address(), a2, a3, a4);
        }
    }

    /** Registers a shared memory region with the host. */
    static void registerRegion(String name, MemorySegment seg) {
        callWithName(SHAREMEM, name, seg.address(), seg.byteSize(), 0L);
    }

    /** Registers a shared memory region with the host. {@code buf} must be a direct ByteBuffer. */
    static void registerRegion(String name, ByteBuffer buf) {
        if (!buf.isDirect()) throw new IllegalArgumentException("buf must be a direct ByteBuffer");
        callWithName(SHAREMEM, name, directAddress(buf), buf.capacity(), 0L);
    }

    /** Tells the host to take the base snapshot. Execution resumes here on every restore. */
    static void snapshot() {
        call(SNAPSHOT, 0L, 0L, 0L, 0L);
    }

    /**
     * Signals the host that a segment boundary has been reached.
     * The shim writes the next {@code [seed, until]} pair to INPUT so that
     * {@code Source.step()} can read it via the {@code NYX_SEGMENT_SUPPLIER}.
     * Always called from Scheduler context ({@code CURRENT_NODE == null}).
     */
    static void segmentBoundary() {
        call(SEGMENT_BOUNDARY, 0L, 0L, 0L, 0L);
    }

    /**
     * Deferred boot snapshot: issued at the first random draw instead of at startup.
     * The host takes the boot snapshot and writes the first segment to INPUT before resuming.
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

    /** Returns the native address of a direct ByteBuffer using Panama FFM (no JNI). */
    static long directAddress(ByteBuffer buf) {
        return MemorySegment.ofBuffer(buf).address();
    }
}
