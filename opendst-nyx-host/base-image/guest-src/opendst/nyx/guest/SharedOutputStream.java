package opendst.nyx.guest;

import java.io.OutputStream;

/**
 * OutputStream that writes bytes into the nyx-lite shared output buffer.
 * Used to redirect System.out so simulation log lines reach the host.
 *
 * <p>Buffers bytes until a newline, then flushes the complete line.
 * When a line containing {@code "message":"stopped"} is detected, issues
 * {@link Hypercall#done(long)} (EXECDONE) so the host's {@code vm.run()} returns.
 * The host then immediately applies the snapshot, rewinding this guest thread —
 * no spin or halt needed.
 */
final class SharedOutputStream extends OutputStream {

    private final SharedMemory shm;
    private final byte[] lineBuf = new byte[4096];
    private int pos = 0;

    SharedOutputStream(SharedMemory shm) {
        this.shm = shm;
    }

    @Override
    public void write(int b) {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) {
            byte b = buf[i];
            if (pos < lineBuf.length) lineBuf[pos++] = b;
            if (b == '\n') flushLine();
        }
    }

    private void flushLine() {
        if (pos == 0) return;
        var line = new byte[pos];
        System.arraycopy(lineBuf, 0, line, 0, pos);
        pos = 0;

        shm.writeLine(line);

        // Issue EXECDONE when the simulator emits "stopped" so the host's vm.run()
        // returns. The host immediately applies the snapshot, rewinding this thread.
        if (new String(line, java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"message\":\"stopped\"")) {
            shm.finishIteration(0L);
            // The host applies the snapshot here — this code is never reached during
            // normal iteration. On the last iteration the host exits and kills the VM.
        }
    }
}
