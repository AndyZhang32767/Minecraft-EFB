package dev.airac.nav;

import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Simplified radio reception inside horizontal chart coverage; no terrain attenuation. */
public final class RadioNavigation {
    private RadioNavigation() {}
    public static boolean isStation(Kind kind) { return kind == Kind.NDB || kind == Kind.VOR || kind == Kind.ILS; }
    public static boolean validFrequency(Kind kind, int frequency) {
        if (kind == Kind.NDB) return frequency >= 190 && frequency <= 1750;
        if (frequency < 108000 || frequency > 117950 || frequency % 50 != 0) return false;
        boolean localizer = frequency < 112000 && (frequency / 100) % 2 == 1;
        return kind == Kind.ILS ? localizer : kind == Kind.VOR && !localizer;
    }
    public static int parseFrequency(Kind kind, String value) {
        try {
            int frequency = new BigDecimal(value.trim()).multiply(BigDecimal.valueOf(kind == Kind.NDB ? 1 : 1000)).intValueExact();
            if (!validFrequency(kind, frequency)) throw new IllegalArgumentException();
            return frequency;
        } catch (RuntimeException ex) { throw new IllegalArgumentException("invalid_frequency"); }
    }
    public static String frequency(Kind kind, int value) {
        return value == 0 ? "" : kind == Kind.NDB ? Integer.toString(value)
                : String.format(Locale.ROOT, "%.2f", value / 1000.0);
    }
    public static String unit(Kind kind) { return kind == Kind.NDB ? "kHz" : "MHz"; }
    public static int randomFrequency(ChartData chart, Kind kind) {
        if (!isStation(kind)) throw new IllegalArgumentException("invalid_station");
        List<Integer> all = new ArrayList<>(), unused = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Point p : chart.points) if (p.kind == kind) used.add(p.frequency);
        if(kind==Kind.ILS)for(Point p:RunwayIls.stations(chart))used.add(p.frequency);
        int start = kind == Kind.NDB ? 190 : 108000, end = kind == Kind.NDB ? 1750 : 117950;
        for (int f = start; f <= end; f += kind == Kind.NDB ? 1 : 50) if (validFrequency(kind, f)) {
            all.add(f); if (!used.contains(f)) unused.add(f);
        }
        List<Integer> choices = unused.isEmpty() ? all : unused;
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }
    public static Point createStation(ChartData chart, Kind kind, double x, double y, double z) {
        if (kind!=Kind.NDB && kind!=Kind.VOR) throw new IllegalArgumentException("invalid_station");
        int index = 1; String ident;
        Set<String> used = new HashSet<>();
        for (Point p : chart.points) if (p.kind != Kind.AIRPORT) used.add(p.ident.toUpperCase(Locale.ROOT));
        do { ident = kind.name() + index++; } while (used.contains(ident));
        Point p = new Point(ident, kind, ChartData.blockCenter(x), y, ChartData.blockCenter(z));
        p.frequency = randomFrequency(chart, kind); return p;
    }
    public static void validateStation(Point p) {
        if (!isStation(p.kind) || !validFrequency(p.kind, p.frequency)
                || !ChartData.coordinate(p.x) || !ChartData.coordinate(p.y) || !ChartData.coordinate(p.z)
                || !Double.isFinite(p.radius) || p.radius < 1 || p.radius > 1_000_000
                || p.kind == Kind.VOR && (p.vorMode == null || !Double.isFinite(p.declination) || Math.abs(p.declination) > 180)
                || p.kind == Kind.ILS && (!Double.isFinite(p.course) || p.course < 0 || p.course >= 360
                    || !Double.isFinite(p.glideSlope) || p.glideSlope <= 0 || p.glideSlope >= 90))
            throw new IllegalArgumentException("invalid_station");
    }
    public record Signal(Point station, double distance, double bearing, double relativeBearing,
                         double radial, double localizer, double glideSlope) {
        public boolean hasBearing() { return station.kind == Kind.NDB || station.kind == Kind.VOR && station.vorMode != VorMode.DME; }
        public boolean hasDme() { return station.kind == Kind.VOR && station.vorMode != VorMode.VOR; }
    }
    /** Closest VOR whose horizontal coverage includes the player. Outside every radius, none. */
    public static Point nearestVor(ChartData chart, double x, double z) {
        Point best=null; double bestDistance=Double.POSITIVE_INFINITY;
        if(chart==null || !Double.isFinite(x) || !Double.isFinite(z))return null;
        for(Point p:chart.points) if(p.kind==Kind.VOR) {
            double distance=Math.hypot(p.x-x, p.z-z);
            if(distance<=p.radius && (distance<bestDistance || distance==bestDistance && (best==null || p.id.compareTo(best.id)<0))) {
                best=p; bestDistance=distance;
            }
        }
        return best;
    }
    /** Point RADIO 1 at that VOR. Returns whether the stored tuning changed. */
    public static boolean tuneNearestVor(Radio radio, ChartData chart, double x, double z) {
        Point station=nearestVor(chart,x,z);
        int frequency=station==null?0:station.frequency;
        if(radio.mode==Kind.VOR && radio.frequency==frequency)return false;
        radio.mode=Kind.VOR; radio.frequency=frequency; return true;
    }
    public static Signal receive(ChartData chart, Radio radio, double x, double y, double z, double heading) {
        if (radio == null || !isStation(radio.mode) || !validFrequency(radio.mode, radio.frequency)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        Point best = null; double bestDistance = Double.POSITIVE_INFINITY;
        List<Point> candidates=radio.mode==Kind.ILS?RunwayIls.stations(chart):chart.points;
        for (Point p : candidates) if (p.kind == radio.mode && p.frequency == radio.frequency) {
            double distance = Math.hypot(p.x - x, p.z - z);
            if (distance <= p.radius && (distance < bestDistance || distance == bestDistance && (best == null || p.id.compareTo(best.id) < 0))) {
                best = p; bestDistance = distance;
            }
        }
        if (best == null) return null;
        double bearing = NavMath.bearing(best.x-x, best.z-z);
        double radial = NavMath.normalize(NavMath.bearing(x-best.x,z-best.z)-best.declination);
        double approachBearing = NavMath.bearing(x-best.x,z-best.z);
        double loc = signed(approachBearing - (best.course+180));
        double slope = Math.toDegrees(Math.atan2(y-best.y,bestDistance))-best.glideSlope;
        return new Signal(best, Math.hypot(bestDistance,y-best.y), bearing, NavMath.normalize(bearing-heading), radial, loc, slope);
    }
    private static double signed(double angle) { return NavMath.normalize(angle+180)-180; }
}
