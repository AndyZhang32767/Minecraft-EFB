package dev.airac.nav;

import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import java.util.*;

/** MCDU route editing. Resolve the whole change before touching a personal plan. */
public final class RoutePlanner {
    private RoutePlanner() {}
    public static Point byIdent(ChartData data, String ident) {
        return data.points.stream().filter(p -> p.ident.equalsIgnoreCase(ident.trim()))
                .min(Comparator.comparingInt(p -> p.kind == Kind.AIRPORT ? 1 : 0)).orElse(null);
    }
    public static Point airportByIdent(ChartData data, String ident) {
        return data.points.stream().filter(p -> p.kind == Kind.AIRPORT && p.ident.equalsIgnoreCase(ident.trim()))
                .findFirst().orElse(null);
    }
    public static List<String> airwayPath(ChartData data, String name, String entry, String exit) {
        if (entry.equals(exit) || data.point(entry) == null || data.point(exit) == null)
            throw new IllegalArgumentException("invalid_endpoints");
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Airway airway : data.airways) if (airway.name.equalsIgnoreCase(name.trim())) {
            for (int i = 1; i < airway.pointIds.size(); i++) {
                if(airway.disconnectedBefore(i))continue;
                String a = airway.pointIds.get(i - 1), b = airway.pointIds.get(i);
                graph.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                if (!airway.oneWay) graph.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
            }
        }
        Map<String, String> previous = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(entry); previous.put(entry, null);
        while (!queue.isEmpty() && !previous.containsKey(exit)) {
            String point = queue.removeFirst();
            for (String next : graph.getOrDefault(point, Set.of())) if (!previous.containsKey(next)) {
                previous.put(next, point); queue.addLast(next);
            }
        }
        if (!previous.containsKey(exit)) throw new IllegalArgumentException("airway_not_connected");
        LinkedList<String> path = new LinkedList<>();
        for (String at = exit; at != null; at = previous.get(at)) path.addFirst(at);
        return path;
    }
    public static void endpoints(ChartData data, Route route, Point departure, Point arrival) {
        if (departure == null || arrival == null || departure.kind != Kind.AIRPORT || arrival.kind != Kind.AIRPORT)
            throw new IllegalArgumentException("invalid_airport");
        route.normalizeVias();
        // Preserve enroute work while changing INIT; terminal procedures belong to their airports.
        int previousLast = route.pointIds.size() - 1;
        for (int i = previousLast; i >= 0; i--)
            if (!route.sections.get(i).isEmpty()
                    || (i == previousLast && (route.pointIds.get(i).equals(route.arrivalId) || route.pointIds.get(i).equals(arrival.id)))
                    || (i == 0 && (route.pointIds.get(i).equals(route.departureId) || route.pointIds.get(i).equals(departure.id)))) route.removeAt(i);
        route.departureId = departure.id; route.arrivalId = arrival.id;
        route.departureRunwayId = route.arrivalRunwayId = route.departureProcedureId = route.arrivalProcedureId = null;
        route.departureRunwayEnd = route.arrivalRunwayEnd = "";
        route.departureConfigured = route.arrivalConfigured = false;
        route.performance.ilsFrequency = 0;
        route.editAfter = -1; data.activate(route);
    }
    /** End of the editable enroute section, before the reserved arrival procedure/runway. */
    public static int appendIndex(Route route) {
        route.normalizeVias();
        int star = route.sections.indexOf("STAR");
        return star >= 0 ? star : route.arrivalRunwayId == null ? route.pointIds.size() : Math.max(0, route.pointIds.size() - 1);
    }
    public static int insertionIndex(Route route, int after) {
        int end = route.arrivalRunwayId == null ? route.pointIds.size() : Math.max(0, route.pointIds.size() - 1);
        return after < 0 ? appendIndex(route) : Math.clamp(after + 1, route.departureRunwayId == null ? 0 : 1, end);
    }
    public static int editIndex(Route route) { return insertionIndex(route, route.editAfter); }
    public static void beginGap(Route route, int before) {
        if (!route.disconnectedBefore(before)) throw new IllegalArgumentException("invalid_gap");
        route.editAfter = before - 1;
    }
    public static void appendAtEnd(Route route) { route.editAfter = -1; }
    public static void connectGap(ChartData data, Route route, int before) {
        if (!route.disconnectedBefore(before)) return;
        route.discontinuities.set(before, false); route.vias.set(before, "DIR"); data.activate(route);
    }
    private static void requireEndpoints(Route route) {
        if (route.departureId == null || route.arrivalId == null) throw new IllegalArgumentException("set_dep_arr");
    }
    public static int direct(ChartData data, Route route, int after, String pointId) {
        if (data.point(pointId) == null) throw new IllegalArgumentException("invalid_endpoints");
        return insert(data, route, after, List.of(pointId), List.of("DIR"), false);
    }
    public static int airway(ChartData data, Route route, int after, String entry, String name, String exit) {
        requireEndpoints(route);
        List<String> path = airwayPath(data, name, entry, exit);
        int index = insertionIndex(route, after);
        boolean connected = index > 0 && route.pointIds.get(index - 1).equals(entry);
        List<String> additions = new ArrayList<>(path);
        if (connected) additions.removeFirst();
        List<String> vias = new ArrayList<>(Collections.nCopies(additions.size(), name.trim().toUpperCase(Locale.ROOT)));
        if (!connected) vias.set(0, "DIR");
        return insert(data, route, after, additions, vias, !connected);
    }
    private static int insert(ChartData data, Route route, int after, List<String> fixes, List<String> vias, boolean entryGap) {
        route.normalizeVias();
        int index = insertionIndex(route, after), selected = index - 1;
        for (int n = 0; n < fixes.size(); n++) {
            String id = fixes.get(n);
            if (index > 0 && route.pointIds.get(index - 1).equals(id)) continue;
            // Reaching the downstream fix joins the existing sequence without duplicating it.
            if (index < route.pointIds.size() && route.pointIds.get(index).equals(id)) {
                route.vias.set(index, vias.get(n)); route.discontinuities.set(index, n == 0 && entryGap);
                selected = index++;
            } else {
                route.insertAt(index, id, vias.get(n), n == 0 && entryGap, "");
                selected = index++;
                if (index < route.pointIds.size()) { route.discontinuities.set(index, true); route.vias.set(index, "DIR"); }
            }
        }
        if (route.editAfter >= 0) route.editAfter = selected;
        data.activate(route); return selected;
    }
    public static boolean editableLeg(Route route, int index) {
        return route != null && index >= 0 && index < route.pointIds.size()
                && !(index == 0 && route.departureRunwayId != null)
                && !(index == route.pointIds.size() - 1 && route.arrivalRunwayId != null);
    }
    public static int remove(ChartData data, Route route, int index) {
        if (!editableLeg(route, index)) return index;
        route.removeAt(index); route.editAfter = -1;
        data.activate(route); return Math.min(index, route.pointIds.size() - 1);
    }
    public static int removeAirway(ChartData data, Route route, int index) {
        if (!editableLeg(route, index)) return index;
        String via = route.vias.get(index);
        int from = index, to = index;
        if (!via.equals("DIR")) {
            while (editableLeg(route, from - 1) && route.vias.get(from - 1).equals(via) && !route.disconnectedBefore(from)) from--;
            while (editableLeg(route, to + 1) && route.vias.get(to + 1).equals(via) && !route.disconnectedBefore(to + 1)) to++;
        }
        for (int i = to; i >= from; i--) route.removeAt(i);
        route.editAfter = -1; data.activate(route); return Math.min(from, route.pointIds.size() - 1);
    }
    public static int move(ChartData data, Route route, int index, int step) {
        int next = index + step;
        if (!editableLeg(route, index) || !editableLeg(route, next)) return index;
        route.normalizeVias(); Collections.swap(route.pointIds, index, next);
        Collections.swap(route.sections, index, next);
        for (int i = Math.min(index, next); i <= Math.min(route.vias.size() - 1, Math.max(index, next) + 1); i++) {
            route.vias.set(i, "DIR"); route.discontinuities.set(i, i > 0);
        }
        route.editAfter = -1; data.activate(route); return next;
    }
    public static String procedureRunwayEnd(ChartData data, Procedure p, boolean departure) {
        if (!departure || p.runwayId == null) return p.runwayEnd;
        Runway runway = data.runway(p.runwayId);
        if (runway == null) return "";
        String[] ends = runway.ends().toArray(String[]::new);
        if (ends.length != 2) return p.runwayEnd;
        return ends[0].equals(p.runwayEnd) ? ends[1] : ends[0];
    }
    public static boolean compatible(ChartData data, Procedure p, String airport, String runwayId, String end, boolean departure) {
        return Objects.equals(p.airportId, airport) && (p.runwayId == null
                || Objects.equals(p.runwayId, runwayId) && procedureRunwayEnd(data, p, departure).equals(end));
    }
    public static void terminal(ChartData data, Route route, boolean departure, String runwayId, String end, String procedureId) {
        requireEndpoints(route);
        String airport = departure ? route.departureId : route.arrivalId;
        Runway runway = data.runway(runwayId);
        Procedure procedure = data.procedure(procedureId);
        if (runwayId != null && (runway == null || !runway.airportId.equals(airport)
                || !runway.ends().contains(end))) throw new IllegalArgumentException("invalid_runway_selection");
        if (procedureId != null && (procedure == null || !compatible(data, procedure, airport, runwayId, end, departure)))
            throw new IllegalArgumentException("invalid_procedure");
        route.normalizeVias();
        String section = departure ? "SID" : "STAR";
        String anchorSection = departure ? "DEP" : "ARR";
        for (int i = route.pointIds.size() - 1; i >= 0; i--)
            if (route.sections.get(i).equals(section) || route.sections.get(i).equals(anchorSection)) route.removeAt(i);
        if (departure) {
            route.departureRunwayId = runwayId; route.departureRunwayEnd = end; route.departureProcedureId = procedureId;
            route.departureConfigured = true;
        } else {
            route.arrivalRunwayId = runwayId; route.arrivalRunwayEnd = end; route.arrivalProcedureId = procedureId;
            route.arrivalConfigured = true;
        }
        if (runway != null) {
            int anchor = departure ? 0 : route.pointIds.size();
            route.insertAt(anchor, ChartData.thresholdId(runwayId, end), "DIR", !departure && anchor > 0, anchorSection);
            if (departure && route.pointIds.size() > 1) route.discontinuities.set(1, true);
        }
        if (procedure != null) {
            List<String> fixes = departure ? procedure.pointIds.reversed() : procedure.pointIds;
            int index = departure ? (runway == null ? 0 : 1) : route.pointIds.size() - (runway == null ? 0 : 1);
            boolean joinedDeparture = false, joinedArrival = false;
            // At the departure join, let the existing route own a shared fix.
            // At the arrival join, the preceding route owns it.
            for (int i = 0; i < fixes.size(); i++) {
                String fix = fixes.get(i);
                if (index > 0 && route.pointIds.get(index - 1).equals(fix)) { joinedArrival = !departure; continue; }
                if (departure && i == fixes.size() - 1 && index < route.pointIds.size() && route.pointIds.get(index).equals(fix)) {
                    route.vias.set(index, procedure.name); route.discontinuities.set(index, false); index++; joinedDeparture = true; continue;
                }
                route.insertAt(index++, fix, procedure.name, !departure && i == 0 && !joinedArrival, section);
            }
            if (index < route.pointIds.size()) {
                if (!joinedDeparture) route.discontinuities.set(index, departure);
                if (!departure) route.vias.set(index, procedure.name);
            }
        } else {
            int boundary = departure ? (runway == null ? 0 : 1) : route.pointIds.size() - (runway == null ? 0 : 1);
            if (boundary > 0 && boundary < route.pointIds.size()) route.discontinuities.set(boundary, true);
        }
        if(!departure)RunwayIls.tuneArrival(data,route);
        route.editAfter = -1; data.activate(route);
    }
}
