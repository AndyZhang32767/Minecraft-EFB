package dev.airac.data;

import com.google.gson.*;

/** Explicit disk upgrade; unknown future versions are never rewritten. */
public final class ChartMigration {
    private ChartMigration() {}
    public static ChartData read(JsonObject json) {
        int version = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 1;
        if (version < 1 || version > ChartData.SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported chart version: " + version);
        if (version == 1) {
            if (json.has("airways")) for (JsonElement item : json.getAsJsonArray("airways")) {
                JsonObject airway = item.getAsJsonObject();
                JsonArray points = new JsonArray();
                points.add(airway.get("fromId")); points.add(airway.get("toId"));
                airway.add("pointIds", points); airway.remove("fromId"); airway.remove("toId");
            }
            json.addProperty("schemaVersion", ChartData.SCHEMA_VERSION);
        }
        json.addProperty("schemaVersion", ChartData.SCHEMA_VERSION);
        ChartData data = new Gson().fromJson(json, ChartData.class);
        if (version == 1) {
            for (var point : data.points) if (point.kind == ChartData.Kind.WAYPOINT) { point.name = ""; point.y = 0; }
            for (var route : data.routes) {
                route.normalizeVias();
                if (route.pointIds.size() > 1) {
                    var first = data.point(route.pointIds.getFirst()); var last = data.point(route.pointIds.getLast());
                    if (first != null && first.kind == ChartData.Kind.AIRPORT) route.departureId = first.id;
                    if (last != null && last.kind == ChartData.Kind.AIRPORT) route.arrivalId = last.id;
                }
            }
        }
        if (version < 4) {
            // Preserve old SID geometry and runway compatibility under inbound STAR storage.
            for (var procedure : data.procedures) {
                procedure.pointIds = new java.util.ArrayList<>(procedure.pointIds.reversed());
                var runway = data.runway(procedure.runwayId);
                if (runway != null && runway.ends().size() == 2)
                    procedure.runwayEnd = runway.ends().getFirst().equals(procedure.runwayEnd)
                            ? runway.ends().getLast() : runway.ends().getFirst();
            }
        }
        if (version < 5) {
            for (var route : data.routes) {
                route.normalizeVias();
                boolean active = route.id.equals(data.activeRouteId);
                for (boolean departure : new boolean[]{true, false}) {
                    String airport = departure ? route.departureId : route.arrivalId;
                    String runwayId = departure ? route.departureRunwayId : route.arrivalRunwayId;
                    String end = departure ? route.departureRunwayEnd : route.arrivalRunwayEnd;
                    int index = departure ? 0 : route.pointIds.size()-1;
                    if (index >= 0 && java.util.Objects.equals(airport, route.pointIds.get(index))) {
                        if (data.threshold(data.runway(runwayId), end) != null) {
                            route.pointIds.set(index, ChartData.thresholdId(runwayId, end));
                            route.sections.set(index, departure ? "DEP" : "ARR");
                        } else {
                            if (active && index < data.activeLeg) data.activeLeg--;
                            route.removeAt(index);
                        }
                    }
                }
            }
        }
        if (version < 7) {
            // Remove retired transmitters through the normal topology edit to preserve gaps.
            var retired = data.points.stream().filter(p -> p.kind == ChartData.Kind.ILS).map(p -> p.id).toList();
            for (String id : retired) data.removePoint(id);
            for (var runway : data.runways) {
                runway.ilsEnabled = false; runway.ilsFrequency1 = runway.ilsFrequency2 = 0;
            }
            for (var route : data.routes) route.performance.ilsFrequency = 0;
            for (var radio : data.radios) if (radio.mode == ChartData.Kind.ILS) radio.frequency = 0;
        }
        data.validate();
        return data;
    }
}
