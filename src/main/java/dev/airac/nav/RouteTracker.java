package dev.airac.nav;

import dev.airac.data.ChartData;

/** Captures only the active fix; teleporting never completes an entire route. */
public final class RouteTracker {
    private double previousX, previousZ;
    private boolean initialized;
    public void reset() { initialized = false; }

    public boolean tick(ChartData chart, double x, double z, double captureRadius) {
        ChartData.Point target = chart.target();
        boolean changed = false;
        if (target != null) {
            double distance = NavMath.distance(x, z, target.x, target.z);
            double maxSweptDistance = Math.max(256, captureRadius * 5);
            boolean swept = initialized && NavMath.distance(previousX, previousZ, x, z) < maxSweptDistance
                    && NavMath.segmentDistance(target.x, target.z, previousX, previousZ, x, z) <= captureRadius;
            if (distance <= captureRadius || swept) {
                ChartData.Route route = chart.activeRoute();
                int leg = chart.activeLeg;
                if (route != null && leg >= 0 && leg < route.pointIds.size()) {
                    route.removeAt(leg);
                    if (leg > 0 && leg < route.discontinuities.size()) route.discontinuities.set(leg, false);
                    chart.activeLeg = Math.min(leg, route.pointIds.size());
                } else chart.activeLeg++;
                changed = true;
            }
        }
        previousX = x; previousZ = z; initialized = true;
        return changed;
    }
}
