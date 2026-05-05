package opendst.nyx.guest;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Two off-heap memory regions shared with the nyx-lite host, allocated via
 * {@link Arena} (Panama FFM).
 *
 * <p>Layout:
 * <ul>
 *   <li><b>input</b>  (64 KB, name "opendst-in"):  [4-byte LE length][Plan JSON bytes]
 *   <li><b>output</b> (256 KB, name "opendst-out"): series of newline-terminated log lines,
 *       NUL-terminated when the iteration is done.
 * </ul>
 *
 * <p>Sizes must match the constants in opendst-nyx-host/shim/src/main.rs.
 *
 * <p>Using {@link Arena} rather than {@link java.nio.ByteBuffer#allocateDirect} gives
 * page-aligned allocations, an explicit address without internal-API casting, and
 * cleaner lifecycle management.  All pages are pre-faulted in the constructor so the
 * boot snapshot captures them as present — preventing nyx-lite's
 * {@code read_current_bytes} assert from firing on pages that were never written.
 */
final class SharedMemory {

    static final int INPUT_SIZE  = 64 * 1024;
    static final int OUTPUT_SIZE = 256 * 1024;

    private static final long PAGE_SIZE = 4096;

    // Kept alive for the lifetime of this object so memory is not released.
    private final Arena arena;

    final MemorySegment input;
    final MemorySegment output;

    SharedMemory() {
        arena  = Arena.ofAuto();
        input  = arena.allocate(INPUT_SIZE,  PAGE_SIZE);
        output = arena.allocate(OUTPUT_SIZE, PAGE_SIZE);

        // Pre-fault every page so the boot snapshot records them all as present.
        // Arena.allocate guarantees page alignment, so stride = PAGE_SIZE covers
        // every page exactly — no partial last-page edge case.
        for (long i = 0; i < INPUT_SIZE; i += PAGE_SIZE) {
            input.set(ValueLayout.JAVA_BYTE, i, (byte) 0);
        }
        for (long i = 0; i < OUTPUT_SIZE; i += PAGE_SIZE) {
            output.set(ValueLayout.JAVA_BYTE, i, (byte) 0);
        }

        Hypercall.registerRegion("opendst-in",  input);
        Hypercall.registerRegion("opendst-out", output);
    }

    /** Reads the Plan JSON string that the host wrote into the input region. */
    String readPlan() {
        int len = input.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0);
        if (len <= 0 || len > INPUT_SIZE - 4) {
            throw new IllegalStateException("Invalid plan length in shared memory: " + len);
        }
        byte[] bytes = new byte[len];
        MemorySegment.copy(input, ValueLayout.JAVA_BYTE, 4, bytes, 0, len);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Reads the next segment written by the shim after a snapshot hypercall.
     * Format: [8-byte seed LE][8-byte iteration LE].
     * Returns null if the shim signalled end-of-plan (both fields zero).
     */
    long[] readNextSegment() {
        long seed      = input.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0);
        long iteration = input.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 8);
        if (seed == 0 && iteration == 0) {
            return null; // end-of-plan sentinel
        }
        return new long[]{seed, iteration};
    }

    /**
     * Writes a log line to the output region and advances the write position.
     * The line should already include a trailing newline.
     * Silently truncates if the buffer is full.
     */
    void writeLine(byte[] lineWithNewline) {
        if (outputPos + lineWithNewline.length + 1 <= OUTPUT_SIZE) { // +1 for NUL sentinel
            MemorySegment.copy(lineWithNewline, 0, output, ValueLayout.JAVA_BYTE, outputPos, lineWithNewline.length);
            outputPos += lineWithNewline.length;
        }
    }

    /**
     * Writes the NUL sentinel that tells the host the iteration output is complete,
     * then issues {@link Hypercall#done(long)}.
     */
    void finishIteration(long exitCode) {
        if (outputPos < OUTPUT_SIZE) {
            output.set(ValueLayout.JAVA_BYTE, outputPos, (byte) 0);
        }
        Hypercall.done(exitCode);
        // Execution does NOT continue past here on a normal iteration —
        // the host restores the snapshot on the next call.
        // On the very first warm-up pass (before snapshot) this returns normally.
    }

    /** Resets the output write cursor to the start (called after snapshot restore). */
    void resetOutput() {
        outputPos = 0;
    }

    // Write cursor within the output region (not a ByteBuffer position — MemorySegment
    // is not stateful, so we track the position ourselves).
    private int outputPos = 0;
}
