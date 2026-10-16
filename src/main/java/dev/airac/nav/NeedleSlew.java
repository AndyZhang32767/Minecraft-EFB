package dev.airac.nav;

/** Shortest-turn slew so an ND needle does not snap when the station or bearing changes. */
public final class NeedleSlew {
    private NeedleSlew() {}
    public static double step(double current, double target, double degreesPerSecond, double seconds) {
        if(!Double.isFinite(target))return current;
        target=NavMath.normalize(target);
        if(!Double.isFinite(current))return target;
        double delta=NavMath.normalize(target-current+180)-180;
        double limit=Math.max(0,degreesPerSecond)*Math.max(0,seconds);
        if(Math.abs(delta)<=limit)return target;
        return NavMath.normalize(current+Math.copySign(limit,delta));
    }
}
