package dev.airac.nav;

public final class NavMath {
    private NavMath() {}
    public record Vec(double x, double y) {}
    public static double normalize(double angle) { return (angle % 360 + 360) % 360; }
    public static double heading(double minecraftYaw) { return normalize(minecraftYaw + 180); }
    public static double bearing(double dx, double dz) { return normalize(Math.toDegrees(Math.atan2(dx, -dz))); }
    /** Front pointer, rear reciprocal line, or hidden when the station is only abeam. */
    public static int vorCue(double relative) {
        double signed=normalize(relative+180)-180;
        double abs=Math.abs(signed);
        if(abs<=60)return 0;
        if(abs>=120)return 1;
        return -1;
    }
    public static double reciprocal(double relative) {
        double signed=normalize(relative+180)-180;
        return signed>=0?signed-180:signed+180;
    }
    public static double distance(double x1, double z1, double x2, double z2) { return Math.hypot(x2 - x1, z2 - z1); }
    /** Low altitude uses the configured radius; 150-400 rises linearly and then stays at 200 blocks. */
    public static double captureRadius(double y, double lowAltitudeRadius) {
        if (y <= 150) return lowAltitudeRadius;
        if (y >= 400) return 200;
        return lowAltitudeRadius + (200 - lowAltitudeRadius) * ((y - 150) / 250.0);
    }

    /** Heading up, with screen Y increasing downwards. */
    public static Vec project(double dx, double dz, double heading, double scale) {
        double h = Math.toRadians(heading);
        return new Vec((dx * Math.cos(h) + dz * Math.sin(h)) * scale,
                (-dx * Math.sin(h) + dz * Math.cos(h)) * scale);
    }
    public static Vec unproject(double sx, double sy, double heading, double scale) {
        double h = Math.toRadians(heading);
        return new Vec((sx * Math.cos(h) - sy * Math.sin(h)) / scale,
                (sx * Math.sin(h) + sy * Math.cos(h)) / scale);
    }
    /** Distance from a fix to the player's swept movement segment. */
    public static double segmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : Math.clamp(((px - ax) * dx + (pz - az) * dz) / len, 0, 1);
        return distance(px, pz, ax + t * dx, az + t * dz);
    }
}
