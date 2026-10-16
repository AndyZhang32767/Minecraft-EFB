package dev.airac.sync;

import com.google.gson.Gson;
import dev.airac.data.ChartData;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

/** Only the permanent chart is public; flight plans and ND progress stay private. */
public final class SharedCharts {
    private static final Gson GSON = new Gson();
    public static final int MAX_BYTES = 512 * 1024;
    public static ChartData copy(ChartData source) {
        var shared = new ChartData();
        shared.points = source.points;
        shared.runways = source.runways;
        shared.airways = source.airways;
        shared.procedures = source.procedures;
        return GSON.fromJson(GSON.toJson(shared), ChartData.class);
    }
    public static byte[] encode(ChartData data) {
        var shared = copy(data);
        validate(shared);
        var json = GSON.toJsonTree(shared).getAsJsonObject();
        json.remove("radios");
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Shared chart exceeds 512 KiB");
        return bytes;
    }
    public static ChartData decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Shared chart exceeds 512 KiB");
        var json = com.google.gson.JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (json.has("radios")) throw new IllegalArgumentException("Radio tuning cannot be uploaded");
        var data = GSON.fromJson(json, ChartData.class);
        if (data == null) throw new IllegalArgumentException("Empty shared chart");
        validate(data);
        return data;
    }
    private static void validate(ChartData data) {
        if (data.points == null || data.runways == null || data.airways == null || data.routes == null || data.procedures == null)
            throw new IllegalArgumentException("Missing chart lists");
        if (data.points.size() > 2048 || data.runways.size() > 2048 || data.airways.size() > 4096 || data.procedures.size() > 4096 || !data.routes.isEmpty())
            throw new IllegalArgumentException("Too many chart objects");
        if (!data.routes.isEmpty() || data.activeRouteId != null || data.activeLeg != 0)
            throw new IllegalArgumentException("Flight plans cannot be uploaded");
        data.validate();
        if (data.points.size() > 2048 || data.runways.size() > 2048 || data.airways.size() > 4096 || data.procedures.size() > 4096)
            throw new IllegalArgumentException("Too many chart objects");
        var identifiers = new HashSet<String>();
        for (var p : data.points) {
            text(p.id, 80); text(p.ident, 80); text(p.name, 80); text(p.note, 240);
            if (p.ident.isBlank() || !identifiers.add((p.kind == ChartData.Kind.AIRPORT ? "APT:" : "NAV:") + p.ident.toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate or empty identifier");
        }
        for (var r : data.runways) { text(r.id, 80); text(r.name, 80); }
        for (var p : data.procedures) { text(p.id, 80); text(p.name, 80); text(p.runwayEnd, 80); }
        for (var a : data.airways) { text(a.id, 80); text(a.name, 80); }
    }
    private static void text(String value, int max) {
        if (value == null || value.length() > max) throw new IllegalArgumentException("Chart text too long");
    }
    public static void apply(ChartData target, ChartData shared) {
        var copy = copy(shared);
        for (var procedure : target.procedures) {
            var replacement = copy.procedure(procedure.id);
            if (replacement == null || !GSON.toJson(procedure).equals(GSON.toJson(replacement)))
                target.invalidateProcedureSelections(procedure.id);
        }
        var removed = target.points.stream().map(p -> p.id).filter(id -> copy.point(id) == null).toList();
        for (String id : removed) target.removePoint(id);
        target.points = copy.points; target.runways = copy.runways; target.airways = copy.airways; target.procedures = copy.procedures;
        // A cached personal plan may refer to points deleted while this player was offline.
        for (var route : target.routes) {
            route.normalizeVias();
            for (int i = route.pointIds.size() - 1; i >= 0; i--) if (target.point(route.pointIds.get(i)) == null) {
                route.removeAt(i);
            }
            if (target.point(route.departureId) == null) route.departureId = null;
            if (target.point(route.arrivalId) == null) route.arrivalId = null;
        }
        target.reconcileTerminals();
        target.refreshApproachFrequencies();
        target.normalizeNavigation();
    }
    private SharedCharts() {}
}
