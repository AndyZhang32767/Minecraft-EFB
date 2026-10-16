package dev.airac.nav;

/** Front-course localizer interception and geometric glide-path guidance from a received ILS. */
public final class ApproachGuidance {
    public static boolean captureAllowed(RadioNavigation.Signal signal,double x,double z) {
        return signal!=null && Math.abs(signal.localizer())<=30 && inboundDistance(signal,x,z)>-RunwayIls.TOUCHDOWN_OFFSET;
    }
    private static double inboundDistance(RadioNavigation.Signal signal,double x,double z) {
        var station=signal.station();double h=Math.toRadians(station.course);
        return (station.x-x)*Math.sin(h)-(station.z-z)*Math.cos(h);
    }
    public static double heading(RadioNavigation.Signal signal,double x,double z,double speed) {
        var station=signal.station();double h=Math.toRadians(station.course);
        double before=inboundDistance(signal,x,z)-Math.max(10,speed*2);
        double along=before>0?-before:RunwayIls.TOUCHDOWN_OFFSET;
        double aimX=station.x+Math.sin(h)*along,aimZ=station.z-Math.cos(h)*along;
        return NavMath.bearing(aimX-x,aimZ-z);
    }
    public static double verticalSpeed(RadioNavigation.Signal signal,double x,double y,double z,double speed,double maxRate) {
        var station=signal.station();double slope=Math.tan(Math.toRadians(station.glideSlope));
        double target=station.y+Math.max(0,inboundDistance(signal,x,z))*slope;
        return Math.clamp(-speed*slope+(target-y)*.5,-maxRate,maxRate);
    }
    private ApproachGuidance() {}
}
