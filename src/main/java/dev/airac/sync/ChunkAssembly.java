package dev.airac.sync;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

/** One bounded, ordered transfer per connection. No unbounded chunk maps. */
public final class ChunkAssembly {
    public static final int CHUNK_BYTES = 20 * 1024;
    public static final int MAX_CHUNKS = (SharedCharts.MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    private UUID id;
    private int next, total;
    private long started;
    private ByteArrayOutputStream bytes;
    public byte[] accept(UUID transfer, int index, int count, byte[] chunk, long now) {
        if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count || chunk.length > CHUNK_BYTES)
            throw new IllegalArgumentException("Invalid chart fragment");
        if (index == 0) {
            id = transfer; next = 0; total = count; started = now; bytes = new ByteArrayOutputStream();
        }
        if (bytes == null || !transfer.equals(id) || count != total || index != next || now - started > 10_000)
            throw new IllegalArgumentException("Incomplete or expired chart transfer");
        if (bytes.size() + chunk.length > SharedCharts.MAX_BYTES) throw new IllegalArgumentException("Chart too large");
        bytes.writeBytes(chunk); next++;
        if (next != total) return null;
        byte[] result = bytes.toByteArray(); reset(); return result;
    }
    public void reset() { id = null; bytes = null; next = total = 0; }
}
