package dev.airac.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Atomic local storage. A failed read must never overwrite the original file. */
public final class ChartStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root;
    public ChartStore(Path root) { this.root = root; }
    public static String key(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public Path directory(String worldIdentity, String dimension) {
        return root.resolve("charts").resolve(key(worldIdentity)).resolve(key(dimension));
    }
    public ChartData load(String identity, String dimension, String label) throws IOException {
        Path path = directory(identity, dimension).resolve("chart.json");
        if (!Files.exists(path)) {
            ChartData data = new ChartData();
            data.worldName = label;
            return data;
        }
        try {
            String original = Files.readString(path, StandardCharsets.UTF_8);
            var json = JsonParser.parseString(original).getAsJsonObject();
            boolean migrated = !json.has("schemaVersion") || json.get("schemaVersion").getAsInt() != ChartData.SCHEMA_VERSION;
            ChartData data = ChartMigration.read(json);
            if (migrated) {
                // Dedicated upgrade backup survives subsequent ordinary saves.
                int oldVersion = JsonParser.parseString(original).getAsJsonObject().has("schemaVersion")
                        ? JsonParser.parseString(original).getAsJsonObject().get("schemaVersion").getAsInt() : 1;
                Path backup = path.resolveSibling("chart.v" + oldVersion + ".json.bak");
                if (!Files.exists(backup)) Files.copy(path, backup);
                writeAtomic(path, GSON.toJson(data), true);
            }
            return data;
        } catch (RuntimeException e) { throw new IOException("Invalid chart: " + path, e); }
    }
    public static String personalIdentity(String identity, String playerId) {
        return identity + "|player:" + playerId;
    }
    /** Claim legacy private settings once; other accounts inherit only navigation data. */
    public ChartData loadPersonal(String identity, String dimension, String label, String playerId) throws IOException {
        String personal = personalIdentity(identity, playerId);
        if (Files.exists(directory(personal, dimension).resolve("chart.json"))) return load(personal, dimension, label);
        ChartData data = load(identity, dimension, label);
        if (!data.routes.isEmpty() || data.radios.stream().anyMatch(r -> r.frequency != 0 || r.mode != ChartData.Kind.NDB)) {
            Path owner = root.resolve("charts").resolve(key(identity)).resolve("plans-owner.txt");
            Files.createDirectories(owner.getParent());
            try { Files.writeString(owner, playerId, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { /* Another account already claimed the old plans. */ }
            if (!Files.readString(owner, StandardCharsets.UTF_8).trim().equals(playerId)) {
                data.clearRoutes(); data.clearRadios();
            }
        }
        save(personal, dimension, data);
        return data;
    }

    public void save(String identity, String dimension, ChartData data) throws IOException {
        data.validate();
        writeAtomic(directory(identity, dimension).resolve("chart.json"), GSON.toJson(data), true);
    }
    public NdSettings loadSettings() throws IOException {
        Path path = root.resolve("settings.json");
        if (!Files.exists(path)) return new NdSettings();
        try {
            String json = Files.readString(path).replaceAll("\"mode\"\\s*:\\s*\"(?:LS|VOR)\"", "\"mode\": \"NAV\"");
            NdSettings settings = GSON.fromJson(json, NdSettings.class);
            if (settings == null) throw new IllegalArgumentException("Empty settings");
            settings.validate();
            return settings;
        } catch (RuntimeException e) { throw new IOException("Invalid settings: " + path, e); }
    }
    public void saveSettings(NdSettings settings) throws IOException {
        settings.validate();
        writeAtomic(root.resolve("settings.json"), GSON.toJson(settings), true);
    }
    static void writeAtomic(Path path, String content, boolean backup) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            if (backup && Files.exists(path)) Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
