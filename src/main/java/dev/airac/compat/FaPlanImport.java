package dev.airac.compat;

import dev.airac.nav.PerformanceProfile;
import java.lang.reflect.*;
import java.util.*;

/** Adapts both the released 3.0 plan model and the supplied 3.1 development model. */
@SuppressWarnings({"unchecked", "rawtypes"})
final class FaPlanImport {
    private static final String BASE="ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer$";
    static void load(Object plan,PerformanceProfile profile) throws ReflectiveOperationException {
        ClassLoader loader=plan.getClass().getClassLoader();
        try {
            Class<?> waypoint=Class.forName(BASE+"Waypoint",true,loader);
            Class<?> phase=Class.forName(BASE+"Waypoint$PhaseMarker",true,loader);
            Object none=Enum.valueOf(phase.asSubclass(Enum.class),"NONE");
            Constructor<?> ctor=waypoint.getConstructor(int.class,int.class,int.class,int.class,phase,UUID.class);
            List<PerformanceProfile.Node> exported=profile.exportNodes();
            List<Object> points=new ArrayList<>();
            int approach=-1;
            if(profile.arrivalIls!=null) {
                for(int i=0;i<exported.size()-1;i++)if(exported.get(i).distance()>=profile.approachStart){approach=i;break;}
                if(approach<0)approach=exported.size()-2;
            }
            for(int i=0;i<exported.size();i++) {
                var p=exported.get(i);Object marker=none;
                if(profile.arrivalIls!=null) {
                    if(i==exported.size()-1)marker=Enum.valueOf(phase.asSubclass(Enum.class),"LANDING");
                    else if(i==approach)marker=Enum.valueOf(phase.asSubclass(Enum.class),"APPROACH");
                }
                points.add(ctor.newInstance(block(p.x()),block(p.z()),(int)Math.round(p.altitude()),(int)Math.round(profile.speedAt(p.distance())),marker,UUID.randomUUID()));
            }
            plan.getClass().getMethod("load",List.class).invoke(plan,points);
            plan.getClass().getMethod("setTracking",Class.forName(BASE+"ActiveSegment",true,loader)).invoke(plan,new Object[]{null});
        } catch(ClassNotFoundException ex) {
            Class<?> waypoint=Class.forName(BASE+"EnrouteWaypoint",true,loader);
            Class<?> active=Class.forName(BASE+"EnrouteWaypoint$Active",true,loader);
            Constructor<?> ctor=waypoint.getConstructor(int.class,int.class,int.class,int.class,active,UUID.class);
            List<Object> points=new ArrayList<>();
            for(var p:profile.exportNodes())points.add(ctor.newInstance(block(p.x()),block(p.z()),(int)Math.round(p.altitude()),(int)Math.round(profile.speedAt(p.distance())),null,UUID.randomUUID()));
            Class<?> dep=Class.forName(BASE+"DepartureData",true,loader),arr=Class.forName(BASE+"ArrivalData",true,loader);
            Class<?> model=Class.forName(BASE+"FlightPlan",true,loader);
            Object departure=dep.getConstructor().newInstance();
            if(profile.departure!=null && (profile.departure.pointId()==null || !profile.departure.pointId().startsWith("RWY:"))) {
                var origin=profile.departure;
                departure=dep.getConstructor(int.class,int.class,int.class,float.class)
                        .newInstance(block(origin.x()),block(origin.z()),(int)Math.round(origin.altitude()),
                                dep.getMethod("getTakeoffThrust").invoke(departure));
            }
            Object arrival=arr.getConstructor().newInstance();
            if(profile.arrivalIls!=null) {
                // The official 3.0 API has arrival geometry and per-waypoint speed, no standalone APPR SPD field.
                Class<?> minimums=Class.forName(BASE+"ArrivalData$MinimumsType",true,loader);
                arrival=arr.getConstructor(int.class,int.class,int.class,float.class,int.class,minimums,int.class,int.class)
                        .newInstance(block(profile.arrivalIls.x),block(profile.arrivalIls.z),(int)Math.round(profile.arrivalIls.y),
                                arr.getMethod("getLandingThrust").invoke(arrival),arr.getMethod("getMinimums").invoke(arrival),
                                arr.getMethod("getMinimumsType").invoke(arrival),arr.getMethod("getGoAroundAltitude").invoke(arrival),
                                arr.getMethod("getApproachReEntryWaypointIndex").invoke(arrival));
            }
            Object imported=model.getConstructor(dep,List.class,arr).newInstance(departure,points,arrival);
            plan.getClass().getMethod("load",model).invoke(plan,imported);
        }
    }
    static void track(Object plan,int target) throws ReflectiveOperationException {
        ClassLoader loader=plan.getClass().getClassLoader();
        try {
            Class<?> tracking=Class.forName(BASE+"ActiveSegment",true,loader);
            Class<?> direct=Class.forName(BASE+"ActiveSegment$Direct",true,loader);
            plan.getClass().getMethod("setTracking",tracking).invoke(plan,direct.getConstructor(int.class).newInstance(target));
        } catch(ClassNotFoundException ex) {
            List<?> points=(List<?>)plan.getClass().getMethod("getEnrouteData").invoke(plan);
            Class<?> active=Class.forName(BASE+"EnrouteWaypoint$Active",true,loader);
            Object origin=Enum.valueOf(active.asSubclass(Enum.class),"ORIGIN"),next=Enum.valueOf(active.asSubclass(Enum.class),"TARGET");
            Method set=Class.forName(BASE+"EnrouteWaypoint",true,loader).getMethod("setActive",active);
            for(int i=0;i<points.size();i++)set.invoke(points.get(i),i==target?next:i==target-1?origin:null);
        }
    }
    static int block(double value){return (int)Math.floor(value);}
    private FaPlanImport() {}
}
