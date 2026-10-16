package dev.airac.nav;

import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import java.util.*;

/** Runway transmitters are derived geometry, never independent public chart points. */
public final class RunwayIls {
    /** Approximate touchdown zone, down the landing runway from its threshold. */
    public static final double TOUCHDOWN_OFFSET=25;
    private RunwayIls() {}
    public static Point station(ChartData chart, Runway runway, String end) {
        if(runway==null || !runway.ilsEnabled || !runway.ends().contains(end))return null;
        Point airport=chart.point(runway.airportId);
        if(airport==null || airport.kind!=Kind.AIRPORT)return null;
        boolean first=runway.ends().getFirst().equals(end);
        Point p=new Point(airport.ident+"/ILS"+end,Kind.ILS,
                first?runway.x1:runway.x2,airport.y,first?runway.z1:runway.z2);
        p.id="ILS:"+runway.id+":"+end;
        p.frequency=first?runway.ilsFrequency1:runway.ilsFrequency2;
        p.course=NavMath.bearing(first?runway.x2-runway.x1:runway.x1-runway.x2,
                first?runway.z2-runway.z1:runway.z1-runway.z2);
        p.glideSlope=Math.toDegrees(Math.atan(1/runway.ilsSlope));
        return p;
    }
    public static List<Point> stations(ChartData chart) {
        List<Point> result=new ArrayList<>();
        for(Runway r:chart.runways)if(r.ilsEnabled)for(String end:r.ends()) {
            Point p=station(chart,r,end);if(p!=null)result.add(p);
        }
        return result;
    }
    public static Point arrival(ChartData chart,Route route) {
        Runway r=chart.runway(route.arrivalRunwayId);
        return r!=null && Objects.equals(r.airportId,route.arrivalId)?station(chart,r,route.arrivalRunwayEnd):null;
    }
    public static void tuneArrival(ChartData chart,Route route) {
        Point p=arrival(chart,route);
        route.performance.ilsFrequency=p==null?0:p.frequency;
    }
    public static void assignFrequencies(ChartData chart,Runway r) {
        Set<Integer> used=new HashSet<>();
        for(Point p:stations(chart))if(!p.id.startsWith("ILS:"+r.id+":"))used.add(p.frequency);
        if(!RadioNavigation.validFrequency(Kind.ILS,r.ilsFrequency1))r.ilsFrequency1=unused(used);
        used.add(r.ilsFrequency1);
        if(!RadioNavigation.validFrequency(Kind.ILS,r.ilsFrequency2) || r.ilsFrequency1==r.ilsFrequency2)r.ilsFrequency2=unused(used);
    }
    private static int unused(Set<Integer> used) {
        List<Integer> all=new ArrayList<>(),free=new ArrayList<>();
        for(int f=108000;f<112000;f+=50)if(RadioNavigation.validFrequency(Kind.ILS,f)) {
            all.add(f);if(!used.contains(f))free.add(f);
        }
        Collections.shuffle(free.isEmpty()?all:free);
        return (free.isEmpty()?all:free).getFirst();
    }
}
