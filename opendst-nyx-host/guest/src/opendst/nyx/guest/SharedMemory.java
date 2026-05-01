package opendst.nyx.guest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Two direct ByteBuffer regions shared with the nyx-lite host.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>input</b>  (64 KB, name "opendst-in"):  [4-byte LE length][Plan JSON bytes]
 *   <li><b>output</b> (64 KB, name "opendst-out"): series of newline-terminated log lines,
 *       NUL-terminated when the iteration is done.
 * </ul>
 *
 * Sizes must match the constants in opendst-nyx-host/src/main.rs.
 */
final class SharedMemory {

    static final int INPUT_SIZE  = 64 * 1024;
    static final int OUTPUT_SIZE = 64 * 1024;

    final ByteBuffer input;
    final ByteBuffer output;

    SharedMemory() {
        input  = ByteBuffer.allocateDirect(INPUT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        output = ByteBuffer.allocateDirect(OUTPUT_SIZE);

        Hypercall.registerRegion("opendst-in",  input);
        Hypercall.registerRegion("opendst-out", output);
    }

    /** Reads the Plan JSON string that the host wrote into the input region. */
    String readPlan() {
        input.position(0);
        int len = input.getInt(); // 4-byte LE length prefix
        if (len <= 0 || len > INPUT_SIZE - 4) {
            throw new IllegalStateException("Invalid plan length in shared memory: " + len);
        }
        byte[] bytes = new byte[len];
        input.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Reads the next segment written by the shim after a snapshot hypercall.
     * Format: [8-byte seed LE][8-byte iteration LE].
     * Returns null if the shim signalled end-of-plan (both fields zero).
     */
    long[] readNextSegment() {
        input.position(0);
        long seed = input.getLong();
        long iteration = input.getLong();
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
        if (output.remaining() > lineWithNewline.length + 1) { // +1 for NUL sentinel
            output.put(lineWithNewline);
        }
    }

    /**
     * Writes the NUL sentinel that tells the host the iteration output is complete,
     * then issues {@link Hypercall#done(long)}.
     */
    void finishIteration(long exitCode) {
        if (output.hasRemaining()) {
            output.put((byte) 0); // NUL sentinel
        }
        Hypercall.done(exitCode);
        // Execution does NOT continue past here on a normal iteration —
        // the host restores the snapshot on the next call.
        // On the very first warm-up pass (before snapshot) this returns normally.
    }

    /** Resets the output write cursor to the start (called after snapshot restore). */
    void resetOutput() {
        output.position(0);
    }
}
