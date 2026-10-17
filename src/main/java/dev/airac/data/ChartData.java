package dev.airac.data;

import java.util.*;
import java.util.function.Predicate;

/** Coordinates use Minecraft blocks: north is -Z, east is +X. */
public final class ChartData {
    public static final int SCHEMA_VERSION = 7;
    public int schemaVersion = SCHEMA_VERSION;
    public String worldName = "";
    public List<Point> points = new ArrayList<>();
    public List<Runway> runways = new ArrayList<>();
    public List<Procedure> procedures = new ArrayList<>();
    public List<Route> routes = new ArrayList<>();
    public List<Airway> airways = new ArrayList<>();
    public String activeRouteId;
    public int activeLeg;

    public enum Kind { WAYPOINT, AIRPORT, NDB, VOR, ILS }
    public enum VorMode { VOR, DME, VORDME }
    /** Two personal receivers; never part of shared chart packets. Zero means untuned. */
    public static final class Radio {
        public Kind mode = Kind.NDB;
        public int frequency;
        /** While set, tuning follows the nearest in-range VOR. Approach ILS stays on RADIO 1. */
        public boolean auto;
    }
    public List<Radio> radios = new ArrayList<>(List.of(new Radio(), new Radio()));
    public void clearRadios() { radios = new ArrayList<>(List.of(new Radio(), new Radio())); }

    public static final class Point {
        public String id = UUID.randomUUID().toString();
        public String ident = "";
        public String name = "";
        public String note = "";
        public Kind kind = Kind.WAYPOINT;
        public double x, y, z;
        /** Frequencies are exact integer kHz, including VHF channels (e.g. 110300). */
        public int frequency;
        public VorMode vorMode = VorMode.VORDME;
        public double radius = 2048, declination, glideSlope = 3, course;
        public Point() {}
        public Point(String ident, Kind kind, double x, double y, double z) {
            this.ident = ident;
            this.name = kind == Kind.AIRPORT ? ident : "";
            this.kind = kind;
            this.x = x; this.y = kind == Kind.WAYPOINT ? 0 : y; this.z = z;
        }
    }

    public static final class Runway {
        public String id = UUID.randomUUID().toString();
        public String airportId;
        public String name = "09/27";
        public double x1, z1, x2, z2, elevation;
        public double width = 16;
        public boolean ilsEnabled;
        /** Horizontal blocks per one vertical block. Both ends share the same glide path. */
        public double ilsSlope = 20;
        public int ilsFrequency1, ilsFrequency2;
        public List<String> ends() { return Arrays.stream(name.split("/")).map(String::trim).map(v -> v.toUpperCase(Locale.ROOT)).toList(); }
        public double length() { return Math.hypot(x2 - x1, z2 - z1); }
    }

    /** Stored in STAR (inbound) order, outer fixes toward the airport. SID reverses it. */
    public static final class Procedure {
        public String id = UUID.randomUUID().toString();
        public String airportId, runwayId;
        public String name = "", runwayEnd = "";
        public List<String> pointIds = new ArrayList<>();
    }
    public Runway runway(String id) { return runways.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null); }
    public Procedure procedure(String id) { return procedures.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }

    /** Permanent chart network; never implicitly fed to the ND. */
    public static final class Airway {
        public boolean oneWay;
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public List<String> pointIds = new ArrayList<>();
        /** Incoming breaks; incomplete public airways never imply a connection across a gap. */
        public List<Boolean> discontinuities = new ArrayList<>();
        public void normalizeGaps() {
            if(discontinuities.isEmpty())for(int i=0;i<pointIds.size();i++)discontinuities.add(false);
        }
        public boolean disconnectedBefore(int index) {
            return index>0 && index<discontinuities.size() && Boolean.TRUE.equals(discontinuities.get(index));
        }
    }

    /** Personal performance, in blocks, degrees and seconds; absent legacy fields keep defaults. */
    public static final class Performance {
        public static final int DEFAULT_ILS_FREQUENCY = 109950;
        public boolean configured;
        public double climbPitch = 15, cruiseAltitude = 200, descentRate = 15, descentAltitude = 150, speed = 200;
        public Double referenceAltitude;
        public int ilsFrequency = DEFAULT_ILS_FREQUENCY;
        public double approachSpeed = 20;
        public void validate() {
            if (referenceAltitude != null && !coordinate(referenceAltitude)
                    || !Double.isFinite(climbPitch) || climbPitch < 1 || climbPitch > 60
                    || !coordinate(cruiseAltitude) || !coordinate(descentAltitude) || descentAltitude > cruiseAltitude
                    || !Double.isFinite(descentRate) || descentRate <= 0 || descentRate > 200
                    || !Double.isFinite(speed) || speed < 1 || speed > 200
                    || !Double.isFinite(approachSpeed) || approachSpeed < 1 || approachSpeed > 200
                    || ilsFrequency != 0 && !dev.airac.nav.RadioNavigation.validFrequency(Kind.ILS, ilsFrequency))
                throw new IllegalArgumentException("invalid_performance");
        }
    }

    /** Ordered flight plan selected in the chart; the ND reads this object directly. */
    public static final class Route {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public Performance performance = new Performance();
        public List<String> pointIds = new ArrayList<>();
        /** Incoming airway per point; manually inserted legs use DIR. */
        public List<String> vias = new ArrayList<>();
        public String departureId, arrivalId;
        public String departureRunwayId, arrivalRunwayId, departureProcedureId, arrivalProcedureId;
        public String departureRunwayEnd = "", arrivalRunwayEnd = "";
        public boolean departureConfigured, arrivalConfigured;
        /** Incoming gaps and terminal ownership are aligned with pointIds. */
        public List<Boolean> discontinuities = new ArrayList<>();
        public List<String> sections = new ArrayList<>();
        /** Shared by the ROUTE and F-PLN views, never persisted or uploaded. */
        public transient int editAfter = -1;
        public boolean disconnectedBefore(int index) {
            return index > 0 && index < discontinuities.size() && Boolean.TRUE.equals(discontinuities.get(index));
        }
        public void removeAt(int index) {
            normalizeVias();
            pointIds.remove(index); vias.remove(index); discontinuities.remove(index); sections.remove(index);
            if (index < pointIds.size()) { discontinuities.set(index, index > 0); vias.set(index, "DIR"); }
            if (editAfter >= index) editAfter--;
        }
        public void insertAt(int index, String point, String via, boolean gap, String section) {
            normalizeVias();
            pointIds.add(index, point); vias.add(index, via); discontinuities.add(index, gap && index > 0); sections.add(index, section);
        }
        public void normalizeVias() {
            if (vias.isEmpty()) for (int i = 0; i < pointIds.size(); i++) vias.add("DIR");
            if (discontinuities.isEmpty()) for (int i = 0; i < pointIds.size(); i++) discontinuities.add(false);
            if (sections.isEmpty()) for (int i = 0; i < pointIds.size(); i++) sections.add("");
        }
        public void clear() {
            pointIds.clear(); vias.clear(); discontinuities.clear(); sections.clear();
            departureId = arrivalId = departureRunwayId = arrivalRunwayId = departureProcedureId = arrivalProcedureId = null;
            departureRunwayEnd = arrivalRunwayEnd = ""; departureConfigured = arrivalConfigured = false; editAfter = -1;
        }
    }

    /** Airports have a separate identifier namespace from fixes and radio stations. */
    public boolean identifierUsed(String ident, Kind kind, String exceptId) {
        return points.stream().anyMatch(p -> !Objects.equals(p.id,exceptId)
                && (p.kind==Kind.AIRPORT)==(kind==Kind.AIRPORT) && p.ident.equalsIgnoreCase(ident));
    }

    public Point point(String id) {
        if (id == null) return null;
        Point existing = points.stream().filter(p -> id.equals(p.id)).findFirst().orElse(null);
        if (existing != null || !id.startsWith("RWY:")) return existing;
        // Derived runway thresholds are references to public geometry, never shared waypoint records.
        for (Runway runway : runways) for (String end : runway.ends())
            if (id.equals(thresholdId(runway.id, end))) return threshold(runway, end);
        return null;
    }
    public static String thresholdId(String runwayId, String end) { return "RWY:" + runwayId + ":" + end; }
    public Point threshold(Runway runway, String end) {
        if (runway == null || !runway.ends().contains(end)) return null;
        boolean first = runway.ends().getFirst().equals(end);
        Point airport = points.stream().filter(p -> p.id.equals(runway.airportId)).findFirst().orElse(null);
        Point p = new Point((airport == null ? "" : airport.ident + "/") + end, Kind.WAYPOINT,
                first ? runway.x1 : runway.x2, 0, first ? runway.z1 : runway.z2);
        p.id = thresholdId(runway.id, end); p.y = runway.elevation;
        return p;
    }
    public Route route(String id) {
        if (id == null) return null;
        return routes.stream().filter(r -> id.equals(r.id)).findFirst().orElse(null);
    }
    public Route activeRoute() { return route(activeRouteId); }
    public Point target() {
        Route route = activeRoute();
        return route != null && activeLeg >= 0 && activeLeg < route.pointIds.size() && !route.disconnectedBefore(activeLeg)
                ? point(route.pointIds.get(activeLeg)) : null;
    }
    public void activate(Route route) {
        activeRouteId = route.id;
        activeLeg = 0;
    }
    public void deactivate() { activeRouteId = null; activeLeg = 0; }

    public void removePoint(String id) {
        points.removeIf(p -> id.equals(p.id));
        runways.removeIf(r -> id.equals(r.airportId));
        // A missing fix invalidates a procedure; do not silently shortcut it.
        procedures.removeIf(p -> id.equals(p.airportId) || p.pointIds.contains(id));
        // Split at removed fixes: never invent a new airway across a deleted point.
        List<Airway> split = new ArrayList<>();
        for (Airway airway : airways) {
            if (!airway.pointIds.contains(id)) { split.add(airway); continue; }
            List<String> segment = new ArrayList<>();
            for (int i=0;i<airway.pointIds.size();i++) {
                String fix=airway.pointIds.get(i);
                if(airway.disconnectedBefore(i)) { addSegment(split,airway,segment);segment=new ArrayList<>(); }
                if (id.equals(fix)) { addSegment(split, airway, segment); segment = new ArrayList<>(); }
                else segment.add(fix);
            }
            addSegment(split, airway, segment);
        }
        airways = split;
        for (Route route : routes) {
            route.normalizeVias();
            if (id.equals(route.departureId)) route.departureId = null;
            if (id.equals(route.arrivalId)) route.arrivalId = null;
            // Removing an earlier occurrence must preserve the current target.
            for (int i = route.pointIds.size() - 1; i >= 0; i--) {
                if (id.equals(route.pointIds.get(i))) {
                    if (route.id.equals(activeRouteId) && i < activeLeg) activeLeg--;
                    route.removeAt(i);
                }
            }
        }
        reconcileTerminals();
        normalizeNavigation();
    }

    private static void addSegment(List<Airway> result, Airway source, List<String> points) {
        if (points.size() < 2) return;
        Airway part = new Airway(); part.name = source.name; part.oneWay = source.oneWay; part.pointIds = new ArrayList<>(points); result.add(part);
    }

    public void removeRunway(String id) {
        runways.removeIf(r -> r.id.equals(id));
        procedures.removeIf(p -> Objects.equals(p.runwayId, id)); reconcileTerminals();
    }
    public void removeProcedure(String id) {
        invalidateProcedureSelections(id); procedures.removeIf(p -> p.id.equals(id)); reconcileTerminals();
    }
    /** Edited/deleted public procedures must not leave a stale private terminal sequence. */
    public void invalidateProcedureSelections(String id) {
        for (Route r : routes) {
            if (Objects.equals(r.departureProcedureId, id)) invalidateTerminal(r, true);
            if (Objects.equals(r.arrivalProcedureId, id)) invalidateTerminal(r, false);
        }
    }
    private void invalidateTerminal(Route r, boolean departure) {
        r.normalizeVias();
        String section = departure ? "SID" : "STAR";
        for (int i = r.pointIds.size() - 1; i >= 0; i--) if (r.sections.get(i).equals(section)) {
            if (r.id.equals(activeRouteId) && i < activeLeg) activeLeg--;
            r.removeAt(i);
        }
        int boundary = departure ? 1 : r.pointIds.size() - 1;
        if (boundary > 0 && boundary < r.pointIds.size()) {
            r.discontinuities.set(boundary, true); r.vias.set(boundary, "DIR");
        }
        if (departure) { r.departureProcedureId = null; r.departureConfigured = false; }
        else { r.arrivalProcedureId = null; r.arrivalConfigured = false; }
        r.editAfter = -1;
    }
    /** Keep private selections valid after public airport data changes. */
    public void reconcileTerminals() {
        for (Route r : routes) for (boolean departure : new boolean[]{true, false}) {
            String airport = departure ? r.departureId : r.arrivalId;
            String runwayId = departure ? r.departureRunwayId : r.arrivalRunwayId;
            String end = departure ? r.departureRunwayEnd : r.arrivalRunwayEnd;
            String procedureId = departure ? r.departureProcedureId : r.arrivalProcedureId;
            Runway runway = runway(runwayId);
            boolean badRunway = runwayId != null && (airport == null || runway == null || !Objects.equals(runway.airportId, airport) || !runway.ends().contains(end));
            Procedure procedure = procedure(procedureId);
            boolean badProcedure = procedureId != null && (procedure == null ||
                    !dev.airac.nav.RoutePlanner.compatible(this, procedure, airport, runwayId, end, departure));
            if (badRunway || badProcedure) invalidateTerminal(r, departure);
            if (badRunway) {
                for (int i = r.pointIds.size() - 1; i >= 0; i--)
                    if (r.pointIds.get(i).equals(thresholdId(runwayId, end))) {
                        if (r.id.equals(activeRouteId) && i < activeLeg) activeLeg--;
                        r.removeAt(i);
                    }
                if (departure) { r.departureRunwayId = null; r.departureRunwayEnd = ""; }
                else { r.arrivalRunwayId = null; r.arrivalRunwayEnd = ""; }
            }
        }
        normalizeNavigation();
    }

    public void refreshApproachFrequencies() {
        for(Route r:routes)dev.airac.nav.RunwayIls.tuneArrival(this,r);
    }

    /** An edited active route starts from its first point, avoiding stale leg indexes. */
    public void routeChanged(Route route) {
        if (route.id.equals(activeRouteId)) { activeLeg = 0; }
    }

    public void clearRoutes() { routes.clear(); deactivate(); }
    public void clearAirways() { airways.clear(); }

    public void normalizeNavigation() {
        Route route = activeRoute();
        if (route == null || route.pointIds.isEmpty()) {
            activeRouteId = null;
            activeLeg = 0;
        } else activeLeg = Math.clamp(activeLeg, 0, route.pointIds.size());
    }

    /** Fail closed on corrupt geometry rather than drawing unbounded coordinates. */
    public void validate() {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported chart version: " + schemaVersion);
        if (points == null || runways == null || routes == null || airways == null || procedures == null) throw new IllegalArgumentException("Missing chart lists");
        Set<String> ids = new HashSet<>();
        for (Point p : points) {
            if (p == null || p.id == null || !ids.add(p.id) || p.kind == null || p.kind == Kind.ILS
                    || p.ident == null || p.name == null || p.note == null
                    || !coordinate(p.x) || !coordinate(p.z) || !coordinate(p.y)) {
                throw new IllegalArgumentException("Invalid point");
            }
            if (dev.airac.nav.RadioNavigation.isStation(p.kind)) dev.airac.nav.RadioNavigation.validateStation(p);
        }
        if (radios == null || radios.size() != 2) throw new IllegalArgumentException("Invalid radios");
        for (Radio radio : radios) if (radio == null || !dev.airac.nav.RadioNavigation.isStation(radio.mode)
                || radio.frequency != 0 && !dev.airac.nav.RadioNavigation.validFrequency(radio.mode, radio.frequency))
            throw new IllegalArgumentException("Invalid radio tuning");
        ids.clear();
        for (Runway r : runways) {
            Point airport = r == null ? null : point(r.airportId);
            if (r == null || r.id == null || !ids.add(r.id) || r.name == null
                    || airport == null || airport.kind != Kind.AIRPORT
                    || !coordinate(r.x1) || !coordinate(r.x2) || !coordinate(r.z1) || !coordinate(r.z2)
                    || !coordinate(r.elevation) || !Double.isFinite(r.width) || r.width < 1 || r.width > 512
                    || r.length() < 1 || !Double.isFinite(r.ilsSlope) || r.ilsSlope <= 0 || r.ilsSlope > 1_000_000
                    || r.ilsEnabled && (r.ends().size() != 2 || r.ends().getFirst().equals(r.ends().getLast())
                        || !dev.airac.nav.RadioNavigation.validFrequency(Kind.ILS,r.ilsFrequency1)
                        || !dev.airac.nav.RadioNavigation.validFrequency(Kind.ILS,r.ilsFrequency2)
                        || r.ilsFrequency1 == r.ilsFrequency2)) throw new IllegalArgumentException("Invalid runway");
        }
        ids.clear();
        for (Airway a : airways) {
            if (a == null || a.id == null || !ids.add(a.id) || a.name == null
                    || a.pointIds == null || a.pointIds.isEmpty() || a.pointIds.size() > 2048 || a.discontinuities == null
                    || a.pointIds.stream().anyMatch(id -> point(id) == null))
                throw new IllegalArgumentException("Invalid airway");
            a.normalizeGaps();
            if(a.discontinuities.size()!=a.pointIds.size() || a.discontinuities.contains(null) || a.discontinuities.getFirst())
                throw new IllegalArgumentException("Invalid airway gaps");
            for (int i = 1; i < a.pointIds.size(); i++)
                if (a.pointIds.get(i).equals(a.pointIds.get(i - 1))) throw new IllegalArgumentException("Repeated airway point");
        }
        ids.clear();
        for (Procedure p : procedures) {
            Point airport = p == null ? null : point(p.airportId);
            if (p == null || p.id == null || !ids.add(p.id) || p.name == null || p.name.isBlank() || p.name.length() > 80
                    || airport == null || airport.kind != Kind.AIRPORT || p.runwayEnd == null
                    || p.pointIds == null || p.pointIds.isEmpty() || p.pointIds.size() > 2048
                    || p.pointIds.stream().anyMatch(id -> point(id) == null || id.equals(p.airportId)))
                throw new IllegalArgumentException("Invalid procedure");
            if (p.runwayId != null && (runway(p.runwayId) == null || !runway(p.runwayId).airportId.equals(p.airportId)
                    || !runway(p.runwayId).ends().contains(p.runwayEnd)))
                throw new IllegalArgumentException("Invalid procedure runway");
            for (int i = 1; i < p.pointIds.size(); i++)
                if (p.pointIds.get(i).equals(p.pointIds.get(i - 1))) throw new IllegalArgumentException("Repeated procedure point");
        }
        ids.clear();
        for (Route r : routes) {
            if (r == null || r.id == null || !ids.add(r.id) || r.name == null || r.pointIds == null || r.vias == null || r.discontinuities == null || r.sections == null
                    || r.pointIds.stream().anyMatch(Predicate.not(id -> point(id) != null))) {
                throw new IllegalArgumentException("Invalid route");
            }
            if (r.performance == null) throw new IllegalArgumentException("invalid_performance");
            r.performance.validate();
            r.normalizeVias();
            for (int i = 0; i < r.pointIds.size(); i++) if (r.pointIds.get(i).startsWith("RWY:")) {
                boolean dep = i == 0 && r.departureRunwayId != null && r.pointIds.get(i).equals(thresholdId(r.departureRunwayId, r.departureRunwayEnd));
                boolean arr = i == r.pointIds.size()-1 && r.arrivalRunwayId != null && r.pointIds.get(i).equals(thresholdId(r.arrivalRunwayId, r.arrivalRunwayEnd));
                if (!dep && !arr) throw new IllegalArgumentException("Invalid runway threshold");
            }
            if (r.discontinuities.size() != r.pointIds.size() || r.sections.size() != r.pointIds.size()
                    || r.discontinuities.contains(null) || r.sections.stream().anyMatch(v -> v == null || !Set.of("", "SID", "STAR", "DEP", "ARR").contains(v))
                    || !r.discontinuities.isEmpty() && Boolean.TRUE.equals(r.discontinuities.getFirst())) throw new IllegalArgumentException("Invalid route segments");
            if (r.vias.size() != r.pointIds.size() || r.vias.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 80))
                throw new IllegalArgumentException("Invalid route airways");
            if (r.departureId != null && (point(r.departureId) == null || point(r.departureId).kind != Kind.AIRPORT))
                throw new IllegalArgumentException("Invalid departure");
            if (r.arrivalId != null && (point(r.arrivalId) == null || point(r.arrivalId).kind != Kind.AIRPORT))
                throw new IllegalArgumentException("Invalid arrival");
        }
        reconcileTerminals();
        normalizeNavigation();
    }
    /** Map decluttering counts airway and ordinary plan legs, never terminal procedures. */
    public Set<String> mapConnectedPoints() {
        Set<String> ids = new HashSet<>();
        for (Airway airway : airways)for(int i=1;i<airway.pointIds.size();i++)if(!airway.disconnectedBefore(i)) {
            ids.add(airway.pointIds.get(i-1));ids.add(airway.pointIds.get(i));
        }
        Route route = activeRoute();
        if (route != null) {
            route.normalizeVias();
            Set<List<String>> procedureLegs = new HashSet<>();
            addProcedureLegs(procedureLegs, procedure(route.departureProcedureId), true);
            addProcedureLegs(procedureLegs, procedure(route.arrivalProcedureId), false);
            for (int i = 1; i < route.pointIds.size(); i++)
                if (!route.disconnectedBefore(i) && !procedureLegs.contains(
                        List.of(route.vias.get(i), route.pointIds.get(i - 1), route.pointIds.get(i)))) {
                    ids.add(route.pointIds.get(i - 1)); ids.add(route.pointIds.get(i));
                }
        }
        return ids;
    }
    private static void addProcedureLegs(Set<List<String>> legs, Procedure procedure, boolean departure) {
        if (procedure == null) return;
        String previous = procedure.airportId;
        for (String next : procedure.pointIds.reversed()) {
            legs.add(List.of(procedure.name, departure ? previous : next, departure ? next : previous));
            previous = next;
        }
    }
    /** Integer block coordinates address the center, including negative blocks. */
    public static double blockCenter(double value) { return Math.floor(value) + 0.5; }
    public static boolean coordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000;
    }
}
