package dev.airac.nav;

import com.google.gson.*;
import dev.airac.data.*;
import dev.airac.data.ChartData.*;
import dev.airac.sync.SharedCharts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceProfileTest {
    @TempDir Path root;
    ChartData chart=new ChartData();
    Route route=new Route();
    PerformanceProfileTest() {
        Point a=new Point("A",Kind.AIRPORT,0,100,0),b=new Point("B",Kind.WAYPOINT,0,0,-2000),c=new Point("C",Kind.WAYPOINT,2000,0,-2000);
        chart.points.addAll(List.of(a,b,c));route.pointIds.addAll(List.of(a.id,b.id,c.id));chart.routes.add(route);
        route.performance.configured=true;route.performance.climbPitch=45;route.performance.cruiseAltitude=400;
        route.performance.descentAltitude=100;route.performance.descentRate=10;route.performance.speed=100;
    }
    @Test void routeWithoutRunwaysStaysAtCruiseAndDoesNotMutateThePlan() {
        var p=new PerformanceProfile(chart,route,999);
        assertTrue(p.feasible);assertEquals(4000,p.totalDistance);assertEquals(0,p.topOfClimb,1e-6);
        assertFalse(Double.isFinite(p.topOfDescent));assertTrue(p.markers.isEmpty());assertNull(p.departure);
        assertEquals(400,p.altitudeAt(0));assertEquals(400,p.altitudeAt(4000));
        assertEquals(3,p.nodes.size());assertEquals(3,route.pointIds.size(),"Predicted fixes never mutate the personal route or public chart");
        assertEquals(p.nodes.getFirst().pointId(),p.exportNodes().getFirst().pointId());
    }
    @Test void departureAirportElevationOverridesRunwayAndAircraftAltitude() {
        Runway r=new Runway();r.airportId=chart.points.getFirst().id;r.x2=100;r.elevation=120;chart.runways.add(r);
        route.departureId=r.airportId;route.departureRunwayId=r.id;route.departureRunwayEnd="09";
        route.pointIds.set(0,ChartData.thresholdId(r.id,"09"));
        var profile=new PerformanceProfile(chart,route,1000);
        assertEquals(300,profile.topOfClimb,1e-6);
        assertEquals(100,profile.nodes.getFirst().altitude());
        assertEquals(100,profile.departure.altitude());
        assertEquals(chart.points.get(1).id,profile.exportNodes().stream().filter(n->n.pointId()!=null).findFirst().orElseThrow().pointId());
        route.departureRunwayId=null;route.pointIds.set(0,chart.points.get(1).id);
        var cruise=new PerformanceProfile(chart,route,1000);
        assertEquals(0,cruise.topOfClimb,1e-6);assertEquals(400,cruise.altitudeAt(0));
    }
    @Test void gapAndInsufficientDistanceNeverCreateAFlyableProfile() {
        route.normalizeVias();route.discontinuities.set(1,true);
        assertThrows(IllegalArgumentException.class,()->new PerformanceProfile(chart,route,100));
        assertNull(PerformanceProfile.preview(chart,route,100));route.discontinuities.set(1,false);
        route.performance.speed=200;var p=new PerformanceProfile(chart,route,100);
        assertTrue(p.feasible);assertTrue(p.markers.isEmpty());
    }
    @Test void profilesRemainImmutableWhilePlanPerformanceIsEdited() {
        var old=new PerformanceProfile(chart,route,100);route.performance.speed=20;
        assertEquals(100,old.speed);assertFalse(Double.isFinite(old.topOfDescent));
        assertEquals(400,new PerformanceProfile(chart,route,100).altitudeAt(0));
    }
    @Test void savedReferenceAltitudeKeepsMapPredictionsStableWithoutARunway() {
        chart.points.getFirst().kind=Kind.WAYPOINT;
        route.performance.referenceAltitude=100.0;
        assertEquals(new PerformanceProfile(chart,route,100).topOfClimb,
                new PerformanceProfile(chart,route,800).topOfClimb);
    }
    @Test void invalidNumbersAndChannelsCannotBeSaved() {
        var p=route.performance;
        p.speed=201;assertThrows(IllegalArgumentException.class,p::validate);p.speed=200;
        p.descentRate=Double.NaN;assertThrows(IllegalArgumentException.class,p::validate);p.descentRate=10;
        p.descentAltitude=401;assertThrows(IllegalArgumentException.class,p::validate);p.descentAltitude=100;
        p.climbPitch=0;assertThrows(IllegalArgumentException.class,p::validate);p.climbPitch=45;
        p.ilsFrequency=113000;assertThrows(IllegalArgumentException.class,p::validate);p.ilsFrequency=110300;p.validate();
    }
    @Test void performanceIsPersonalPersistentAndAbsentFromPublicPackets() throws Exception {
        route.performance.ilsFrequency=110300;var second=new Route();chart.routes.add(second);
        assertFalse(second.performance.configured);assertEquals(200,second.performance.speed);
        var store=new ChartStore(root);store.save("p","d",chart);
        var loaded=store.load("p","d","p");assertEquals(100,loaded.routes.getFirst().performance.speed);
        assertEquals(110300,loaded.routes.getFirst().performance.ilsFrequency);
        String publicJson=new String(SharedCharts.encode(chart),java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(publicJson.contains("performance"));assertTrue(SharedCharts.decode(SharedCharts.encode(chart)).routes.isEmpty());
    }
    @Test void oldVersionSixPlansReceiveDefaultsWithoutLosingGeometry() {
        JsonObject json=new Gson().toJsonTree(chart).getAsJsonObject();
        json.getAsJsonArray("routes").get(0).getAsJsonObject().remove("performance");
        var migrated=ChartMigration.read(json);var perf=migrated.routes.getFirst().performance;
        assertFalse(perf.configured);assertEquals(200,perf.speed);assertEquals(route.pointIds,migrated.routes.getFirst().pointIds);
    }
    @Test void frontCourseIlsGuidesTowardCenterlineAndGlidePathOnlyInCoverage() {
        Point apt=new Point("APT",Kind.AIRPORT,0,100,-2000);chart.points.add(apt);
        Runway runway=new Runway();runway.airportId=apt.id;runway.name="36/18";
        runway.z1=-2000;runway.z2=-2100;runway.ilsEnabled=true;runway.ilsFrequency1=110300;runway.ilsFrequency2=110350;
        runway.ilsSlope=1/Math.tan(Math.toRadians(3));chart.runways.add(runway);
        Radio r=new Radio();r.mode=Kind.ILS;r.frequency=110300;
        double altitude=100+1000*Math.tan(Math.toRadians(3));
        var signal=RadioNavigation.receive(chart,r,100,altitude,-1000,0);
        assertNotNull(signal);assertTrue(ApproachGuidance.captureAllowed(signal,100,-1000));
        double heading=ApproachGuidance.heading(signal,100,-1000,100);
        assertTrue(heading>270 && heading<360,"An aircraft east of the front course turns left toward the localizer");
        var centered=RadioNavigation.receive(chart,r,0,altitude,-1000,0);
        assertEquals(-100*Math.tan(Math.toRadians(3)),ApproachGuidance.verticalSpeed(centered,0,altitude,-1000,100,20),1e-6);
        assertFalse(ApproachGuidance.captureAllowed(RadioNavigation.receive(chart,r,0,altitude,-2500,0),0,-2500));
        assertNull(RadioNavigation.receive(chart,r,0,altitude,1000,0));
    }
    @Test void rocketRequestsSupport200ButNeverRepeatWithinOneSecondOrAboveTarget() {
        assertFalse(HighSpeedThrust.shouldFire(200,150,1,1,0));
        assertFalse(HighSpeedThrust.shouldFire(200,150,1,19,0));
        assertTrue(HighSpeedThrust.shouldFire(200,150,1,20,0));
        assertFalse(HighSpeedThrust.shouldFire(200,150,1,1,1));
        assertTrue(HighSpeedThrust.shouldFire(200,150,1,0,Integer.MIN_VALUE));
        assertTrue(HighSpeedThrust.shouldFire(200,150,1,1,1000),"Reconnect resets player age");
        assertFalse(HighSpeedThrust.shouldFire(200,201,1,20,0));
        assertFalse(HighSpeedThrust.shouldFire(200,150,0,20,0));
        assertEquals(0,HighSpeedThrust.command(200,201,1));
        assertTrue(HighSpeedThrust.command(200,150,.5)>.5);
    }
}
