package dev.airac.sync;

import dev.airac.data.ChartData;
import dev.airac.data.ChartStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Runs exclusively on the server thread. Persist before announcing a new revision. */
public final class SharedChartRepository {
    public enum Result { OK, FORBIDDEN, CONFLICT, READ_ONLY, INVALID, SAVE_FAILED }
    public static final class Entry {
        public ChartData chart = new ChartData();
        public byte[] bytes = SharedCharts.encode(chart);
        public long revision;
        public boolean writable = true;
    }
    public final String serverId;
    private final ChartStore store;
    private final Map<String,Entry> entries = new HashMap<>();
    public SharedChartRepository(Path root) throws IOException {
        Files.createDirectories(root);
        Path id = root.resolve("server-id.txt");
        if (!Files.exists(id)) Files.writeString(id, UUID.randomUUID().toString());
        serverId = UUID.fromString(Files.readString(id).trim()).toString();
        store = new ChartStore(root);
    }
    public Entry get(String dimension) {
        return entries.computeIfAbsent(dimension,d -> {
            var entry = new Entry();
            try { entry.chart = store.load("shared",d,"Server EFB"); entry.bytes = SharedCharts.encode(entry.chart); }
            catch (IOException | RuntimeException e) {
                entry.writable = false;
                org.slf4j.LoggerFactory.getLogger("airac-server").error("Preserving unreadable shared chart for {}",d,e);
            }
            return entry;
        });
    }
    public Result update(String dimension, long revision, byte[] bytes, boolean operator) {
        if (!operator) return Result.FORBIDDEN;
        var entry = get(dimension);
        if (!entry.writable) return Result.READ_ONLY;
        if (revision != entry.revision) return Result.CONFLICT;
        ChartData proposed;
        try { proposed = SharedCharts.decode(bytes); }
        catch (RuntimeException e) { return Result.INVALID; }
        try { store.save("shared",dimension,proposed); }
        catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("airac-server").error("Unable to persist shared chart",e);
            return Result.SAVE_FAILED;
        }
        entry.chart = proposed; entry.bytes = bytes.clone(); entry.revision++;
        return Result.OK;
    }
}
