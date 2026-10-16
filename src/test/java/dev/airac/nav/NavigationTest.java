package dev.airac.nav;

import dev.airac.data.ChartData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationTest {
    ChartData chart() {
        var c=new ChartData();var r=new ChartData.Route();
        for(int i=0;i<3;i++) {var p=new ChartData.Point("P"+i,ChartData.Kind.WAYPOINT,i*100,80,0);c.points.add(p);r.pointIds.add(p.id);}
        c.routes.add(r);c.activate(r);return c;
    }
    @Test void cardinalBearingsMatchMinecraftCoordinates() {
        assertEquals(0,NavMath.bearing(0,-10));assertEquals(90,NavMath.bearing(10,0));
        assertEquals(180,NavMath.bearing(0,10));assertEquals(270,NavMath.bearing(-10,0));
        assertEquals(180,NavMath.heading(0));assertEquals(0,NavMath.heading(180));
    }
    @Test void headingUpProjectionPutsEastAheadAtHeading90() {
        var v=NavMath.project(100,0,90,2);assertEquals(0,v.x(),1e-8);assertEquals(-200,v.y(),1e-8);
    }
    @Test void projectionRoundTripsAcrossRotationAndZoom() {
        for(int h=0;h<360;h+=15) {var v=NavMath.project(-750,1120,h,0.13);var w=NavMath.unproject(v.x(),v.y(),h,0.13);
            assertEquals(-750,w.x(),1e-8);assertEquals(1120,w.y(),1e-8);}
    }
    @Test void fastMovementCapturesPointBetweenTicks() {
        var c=chart();var t=new RouteTracker();assertFalse(t.tick(c,-70,0,10));assertTrue(t.tick(c,70,0,10));
        assertEquals("P1",c.target().ident);assertFalse(c.activeRoute().pointIds.contains(c.points.getFirst().id));
    }
    @Test void teleportDoesNotCaptureIntermediateFix() {
        var c=chart();var t=new RouteTracker();t.tick(c,-1000,0,10);assertFalse(t.tick(c,1000,0,10));assertEquals(0,c.activeLeg);
    }
    @Test void capturesOnlyCurrentLegAndCompletesAtLastFix() {
        var c=chart();var t=new RouteTracker();assertTrue(t.tick(c,0,0,16));assertEquals("P1",c.target().ident);
        assertTrue(t.tick(c,100,0,16));assertEquals("P2",c.target().ident);assertTrue(t.tick(c,200,0,16));assertNull(c.target());assertFalse(t.tick(c,200,0,16));
    }
    @Test void resetsDiscardMovementFromPreviousPlan() {
        var c=chart();var t=new RouteTracker();t.tick(c,-50,0,10);t.reset();assertFalse(t.tick(c,50,0,10));
    }
    @Test void dirCaptureRadiusFollowsAltitudeCurve() {
        assertEquals(32,NavMath.captureRadius(150,32));
        assertEquals(116,NavMath.captureRadius(275,32));
        assertEquals(200,NavMath.captureRadius(400,32));
        assertEquals(200,NavMath.captureRadius(800,32));
    }
    @Test void cruiseVorIsHiddenAbeamAndReversedWhenBehind() {
        assertEquals(0,NavMath.vorCue(0));assertEquals(0,NavMath.vorCue(60));
        assertEquals(-1,NavMath.vorCue(90));assertEquals(-1,NavMath.vorCue(-100));
        assertEquals(1,NavMath.vorCue(180));assertEquals(1,NavMath.vorCue(-150));
        assertEquals(0,NavMath.reciprocal(180),1e-9);assertEquals(-30,NavMath.reciprocal(150),1e-9);
    }
    @Test void needleTakesTheShortTurnAndDoesNotSnapAcrossNorth() {
        assertEquals(10,NeedleSlew.step(350,10,100,0.2),1e-9);
        assertEquals(350,NeedleSlew.step(10,350,100,0.2),1e-9);
        assertEquals(90,NeedleSlew.step(Double.NaN,90,140,1),1e-9);
        assertEquals(40,NeedleSlew.step(0,40,140,1),1e-9);
    }
    @Test void highAltitudeRadiusSupportsFastSweptCapture() {
        var c=chart();var t=new RouteTracker();
        assertFalse(t.tick(c,-300,0,200));
        assertTrue(t.tick(c,300,0,200));
    }
}
