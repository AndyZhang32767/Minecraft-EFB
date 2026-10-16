package dev.airac.nav;

/** Demand-based firing: at most one firework per second, no fixed 33 b/s ceiling. */
public final class HighSpeedThrust {
    public static final int FIRE_INTERVAL_TICKS=20;
    public static double command(double target,double current,double previous) {
        if(current>=target)return 0;
        return Math.clamp(previous+(target-current)*.015,0,1);
    }
    public static boolean shouldFire(double target,double current,double thrust,int tick,int lastTick) {
        if(thrust<=0 || current>=target-.5)return false;
        // Player age restarts on reconnect; an earlier world's tick must never suppress thrust.
        if(lastTick==Integer.MIN_VALUE || tick<lastTick)return true;
        return tick-lastTick>=FIRE_INTERVAL_TICKS;
    }
    private HighSpeedThrust() {}
}
