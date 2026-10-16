package dev.airac.nav;

import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import java.util.*;

/** Immutable prediction snapshot. Horizontal distances follow the expanded route polyline. */
public final class PerformanceProfile {
    public record Node(String label, double x, double altitude, double z, double distance, int routeIndex, String pointId) {}
    public record Marker(String label, double x, double z, double altitude, double distance, int beforeIndex) {}
    public final List<Node> nodes;
    public final List<Marker> markers;
    public final double totalDistance, topOfClimb, topOfDescent;
    public final double climbPitch, cruiseAltitude, descentRate, descentAltitude, speed, approachSpeed, approachStart;
    public final int ilsFrequency;
    public final Point arrivalIls;
    public final Node departure;
    public final boolean feasible;
    private final double originAltitude;

    public PerformanceProfile(ChartData chart, Route route, double fallbackElevation) {
        if (route == null || route.performance == null || route.pointIds.size() < 2)
            throw new IllegalArgumentException("perf_route_required");
        Performance p = route.performance; p.validate();
        climbPitch=p.climbPitch; cruiseAltitude=p.cruiseAltitude; descentRate=p.descentRate;
        descentAltitude=p.descentAltitude; speed=p.speed; approachSpeed=p.approachSpeed;
        List<Point> fixes=new ArrayList<>();
        double[] distances=new double[route.pointIds.size()];
        for(int i=0;i<route.pointIds.size();i++) {
            if(route.disconnectedBefore(i)) throw new IllegalArgumentException("perf_discontinuity");
            Point point=chart.point(route.pointIds.get(i));
            if(point==null)throw new IllegalArgumentException("perf_route_required");
            fixes.add(point);
            if(i>0)distances[i]=distances[i-1]+NavMath.distance(fixes.get(i-1).x,fixes.get(i-1).z,point.x,point.z);
        }
        totalDistance=distances[distances.length-1];
        Point first=fixes.getFirst(),last=fixes.getLast();
        boolean runwayStart=first.id.startsWith("RWY:");
        boolean runwayEnd=last.id.startsWith("RWY:");
        Point arrival=runwayEnd?RunwayIls.arrival(chart,route):null;
        arrivalIls=arrival!=null && arrival.frequency==p.ilsFrequency?arrival:null;
        ilsFrequency=arrivalIls==null?0:arrivalIls.frequency;
        Point airport=runwayStart?chart.point(route.departureId):null;
        if(airport==null && runwayStart && first.kind==Kind.AIRPORT)airport=first;
        departure=runwayStart && airport!=null && airport.kind==Kind.AIRPORT
                ?new Node(airport.ident,first.x,airport.y,first.z,0,0,first.id):null;
        originAltitude=runwayStart?(departure!=null?departure.altitude():first.y):cruiseAltitude;
        topOfClimb=runwayStart?Math.max(0,cruiseAltitude-originAltitude)/Math.tan(Math.toRadians(climbPitch)):0;
        topOfDescent=runwayEnd?totalDistance-(cruiseAltitude-descentAltitude)/descentRate*speed:Double.POSITIVE_INFINITY;
        approachStart=arrivalIls==null?Double.POSITIVE_INFINITY:Math.max(topOfDescent,
                totalDistance-Math.max(0,descentAltitude-arrivalIls.y)/Math.tan(Math.toRadians(arrivalIls.glideSlope)));
        feasible=totalDistance>0 && originAltitude<=cruiseAltitude && topOfClimb<=totalDistance
                && (!runwayEnd || topOfClimb<=topOfDescent)
                && (arrivalIls==null || arrivalIls.y<=cruiseAltitude);
        List<Node> all=new ArrayList<>(); List<Marker> computed=new ArrayList<>();
        for(int i=0;i<fixes.size();i++) {
            Point f=fixes.get(i);all.add(new Node(f.ident,f.x,altitudeAt(distances[i]),f.z,distances[i],i,f.id));
        }
        if(feasible)for(String name:List.of("T.C","T.D")) {
            if(name.equals("T.C") && topOfClimb<=0)continue;
            if(name.equals("T.D") && !Double.isFinite(topOfDescent))continue;
            double distance=name.equals("T.C")?topOfClimb:topOfDescent;
            if(distance<=0 || distance>=totalDistance)continue;
            for(int i=1;i<fixes.size();i++)if(distance<=distances[i]) {
                Point a=fixes.get(i-1),b=fixes.get(i);
                double length=distances[i]-distances[i-1];
                double t=length==0?0:Math.clamp((distance-distances[i-1])/length,0,1);
                Marker m=new Marker(name,a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t,cruiseAltitude,distance,i);
                computed.add(m);all.add(new Node(name,m.x,cruiseAltitude,m.z,distance,-1,null));break;
            }
        }
        if(arrivalIls!=null && fixes.getLast().id.equals(ChartData.thresholdId(route.arrivalRunwayId,route.arrivalRunwayEnd))) {
            double h=Math.toRadians(arrivalIls.course);
            double dx=RunwayIls.TOUCHDOWN_OFFSET*Math.sin(h),dz=-RunwayIls.TOUCHDOWN_OFFSET*Math.cos(h);
            int lastIndex=fixes.size()-1;
            for(int i=0;i<all.size();i++) {
                Node n=all.get(i);
                if(n.routeIndex()!=lastIndex)continue;
                all.set(i,new Node(n.label(),n.x()+dx,n.altitude(),n.z()+dz,n.distance()+RunwayIls.TOUCHDOWN_OFFSET,n.routeIndex(),n.pointId()));
            }
        }
        all.sort(Comparator.comparingDouble(Node::distance));
        nodes=List.copyOf(all);markers=List.copyOf(computed);
    }
    /** Waypoints actually loaded into FA. The departure runway threshold is not one of them. */
    public List<Node> exportNodes() {
        return nodes.stream().filter(n -> n.pointId()==null || n.routeIndex()!=0 || !n.pointId().startsWith("RWY:")).toList();
    }
    public double altitudeAt(double distance) {
        if(arrivalIls!=null && distance>=approachStart)
            return Math.min(cruiseAltitude,arrivalIls.y+Math.max(0,totalDistance-distance)*Math.tan(Math.toRadians(arrivalIls.glideSlope)));
        if(distance<topOfClimb)return Math.min(cruiseAltitude,originAltitude+Math.max(0,distance)*Math.tan(Math.toRadians(climbPitch)));
        if(!Double.isFinite(topOfDescent) || distance<=topOfDescent)return cruiseAltitude;
        return Math.max(descentAltitude,cruiseAltitude-Math.max(0,distance-topOfDescent)*descentRate/speed);
    }
    public double speedAt(double distance) { return distance>=approachStart?approachSpeed:speed; }
    public int nextRouteIndex(int nodeIndex) {
        for(int i=Math.max(0,nodeIndex);i<nodes.size();i++)if(nodes.get(i).routeIndex>=0)return nodes.get(i).routeIndex;
        return Integer.MAX_VALUE;
    }
    public static PerformanceProfile preview(ChartData chart, Route route, double elevation) {
        if(route==null || route.performance==null || !route.performance.configured)return null;
        try{return new PerformanceProfile(chart,route,elevation);}catch(IllegalArgumentException ex){return null;}
    }
}
