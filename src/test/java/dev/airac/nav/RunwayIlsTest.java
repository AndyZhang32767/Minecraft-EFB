package dev.airac.nav;

import com.google.gson.Gson;
import dev.airac.data.*;
import dev.airac.data.ChartData.*;
import dev.airac.sync.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunwayIlsTest {
    @TempDir Path root;
    ChartData chart=new ChartData();
    Point airport=new Point("APT",Kind.AIRPORT,0,80,0);
    Runway runway=new Runway();
    RunwayIlsTest() {
        chart.points.add(airport);runway.airportId=airport.id;runway.name="27L/09R";
        runway.x1=100;runway.x2=0;runway.elevation=999;chart.runways.add(runway);
    }
    void enable() { runway.ilsEnabled=true;RunwayIls.assignFrequencies(chart,runway); }
    @Test void endsHaveDistinctChannelsOppositeCoursesAndLiveAirportElevation() {
        assertTrue(RunwayIls.stations(chart).isEmpty());enable();chart.validate();
        var a=RunwayIls.station(chart,runway,"27L");var b=RunwayIls.station(chart,runway,"09R");
        assertEquals(100,a.x);assertEquals(0,b.x);assertEquals(270,a.course);assertEquals(90,b.course);
        assertNotEquals(a.frequency,b.frequency);assertEquals(80,a.y);assertEquals(80,b.y);
        assertEquals(Math.toDegrees(Math.atan(1.0/20)),a.glideSlope,1e-9);
        Radio radio=new Radio();radio.mode=Kind.ILS;radio.frequency=a.frequency;
        assertEquals(0,RadioNavigation.receive(chart,radio,1100,130,0,270).glideSlope(),1e-9);
        airport.y=100;
        assertEquals(100,RunwayIls.station(chart,runway,"27L").y);
        assertEquals(0,RadioNavigation.receive(chart,radio,1100,150,0,270).glideSlope(),1e-9);
        runway.ilsEnabled=false;assertNull(RadioNavigation.receive(chart,radio,1100,150,0,270));
    }
    @Test void starSelectionFillsCorrectEndAndNoIlsClearsApproach() {
        enable();Point dep=new Point("DEP",Kind.AIRPORT,5000,80,0),fix=new Point("FIX",Kind.WAYPOINT,1000,0,0);
        chart.points.addAll(List.of(dep,fix));
        Procedure star=new Procedure();star.name="STAR1";star.airportId=airport.id;star.runwayId=runway.id;
        star.runwayEnd="27L";star.pointIds.add(fix.id);chart.procedures.add(star);
        Route route=new Route();chart.routes.add(route);RoutePlanner.endpoints(chart,route,dep,airport);
        RoutePlanner.terminal(chart,route,false,runway.id,"27L",star.id);
        assertEquals(runway.ilsFrequency1,route.performance.ilsFrequency);
        RoutePlanner.terminal(chart,route,false,runway.id,"09R",null);
        assertEquals(runway.ilsFrequency2,route.performance.ilsFrequency);
        runway.ilsEnabled=false;chart.refreshApproachFrequencies();assertEquals(0,route.performance.ilsFrequency);
    }
    @Test void ilsApproachHasAirportFinalAltitudeAndDefault20SpeedButNoIlsOmitsIt() {
        enable();Point start=new Point("FIX",Kind.WAYPOINT,5000,0,0);chart.points.add(start);
        Route route=new Route();chart.routes.add(route);route.pointIds.add(start.id);
        route.arrivalId=airport.id;route.arrivalRunwayId=runway.id;route.arrivalRunwayEnd="27L";
        route.pointIds.add(ChartData.thresholdId(runway.id,"27L"));
        route.performance.climbPitch=45;route.performance.speed=100;route.performance.descentRate=50;
        route.performance.cruiseAltitude=200;route.performance.descentAltitude=100;
        RunwayIls.tuneArrival(chart,route);
        var profile=new PerformanceProfile(chart,route,80);
        assertTrue(profile.feasible);assertEquals(80,profile.nodes.getLast().altitude());
        assertEquals(75,profile.nodes.getLast().x(),1e-6);assertEquals(0,profile.nodes.getLast().z(),1e-6);
        assertEquals(20,profile.speedAt(profile.nodes.getLast().distance()));assertNotNull(profile.arrivalIls);
        runway.ilsEnabled=false;
        profile=new PerformanceProfile(chart,route,80);
        assertEquals(0,profile.ilsFrequency);assertNull(profile.arrivalIls);
        assertEquals(100,profile.nodes.getLast().x(),1e-6);
        assertEquals(100,profile.speedAt(profile.totalDistance));
    }
    @Test void departureThresholdIsNotExportedAndAFlownFixCannotBeImportedAgain() {
        enable();Point next=new Point("NEXT",Kind.WAYPOINT,5000,0,0),last=new Point("LAST",Kind.WAYPOINT,6000,0,0);
        chart.points.addAll(List.of(next,last));
        Route route=new Route();chart.routes.add(route);chart.activate(route);
        String threshold=ChartData.thresholdId(runway.id,"27L");
        route.departureId=airport.id;route.departureRunwayId=runway.id;route.departureRunwayEnd="27L";
        route.pointIds.addAll(List.of(threshold,next.id,last.id));
        route.performance.climbPitch=45;route.performance.speed=100;route.performance.descentRate=50;
        route.performance.cruiseAltitude=200;route.performance.descentAltitude=100;
        var profile=new PerformanceProfile(chart,route,80);
        assertTrue(profile.nodes.stream().anyMatch(n->threshold.equals(n.pointId())));
        assertTrue(profile.exportNodes().stream().noneMatch(n->threshold.equals(n.pointId())));
        assertTrue(new RouteTracker().tick(chart,100,0,20));
        assertFalse(route.pointIds.contains(threshold));
        var again=new PerformanceProfile(chart,route,80);
        assertTrue(again.nodes.stream().noneMatch(n->threshold.equals(n.pointId())));
        assertTrue(again.exportNodes().stream().noneMatch(n->threshold.equals(n.pointId())));
    }
    @Test void versionSixUpgradeDeletesStationsPreservesGapsAndKeepsOriginalBackup() throws Exception {
        Point a=new Point("A",Kind.WAYPOINT,1000,0,0),old=new Point("OLDILS",Kind.ILS,0,80,0),b=new Point("B",Kind.WAYPOINT,-1000,0,0);
        old.frequency=110300;chart.points.addAll(List.of(a,old,b));
        Route route=new Route();route.pointIds.addAll(List.of(a.id,old.id,b.id));chart.routes.add(route);
        route.performance.ilsFrequency=110300;chart.radios.getFirst().mode=Kind.ILS;chart.radios.getFirst().frequency=110300;
        chart.schemaVersion=6;String original=new Gson().toJson(chart);
        ChartStore store=new ChartStore(root);Path path=store.directory("w","d").resolve("chart.json");
        Files.createDirectories(path.getParent());Files.writeString(path,original);
        var loaded=store.load("w","d","world");
        assertEquals(7,loaded.schemaVersion);assertNull(loaded.point(old.id));assertFalse(loaded.runways.getFirst().ilsEnabled);
        assertTrue(loaded.routes.getFirst().disconnectedBefore(1));assertEquals(List.of(a.id,b.id),loaded.routes.getFirst().pointIds);
        assertEquals(0,loaded.routes.getFirst().performance.ilsFrequency);assertEquals(0,loaded.radios.getFirst().frequency);
        assertEquals(20,loaded.routes.getFirst().performance.approachSpeed);
        assertEquals(original,Files.readString(path.resolveSibling("chart.v6.json.bak")));
        String upgraded=Files.readString(path);store.load("w","d","world");assertEquals(upgraded,Files.readString(path));
    }
    @Test void serverRestartAndSyncPreserveRunwayIlsButKeepPerformancePrivate() throws Exception {
        enable();var repo=new SharedChartRepository(root);
        assertEquals(SharedChartRepository.Result.OK,repo.update("d",0,SharedCharts.encode(chart),true));
        var received=SharedCharts.decode(new SharedChartRepository(root).get("d").bytes);
        assertEquals(2,RunwayIls.stations(received).size());assertEquals(1,received.points.size());
        var route=new Route();route.arrivalId=airport.id;route.arrivalRunwayId=runway.id;route.arrivalRunwayEnd="27L";
        route.performance.approachSpeed=24;chart.routes.add(route);RunwayIls.tuneArrival(chart,route);
        received.runways.getFirst().ilsEnabled=false;SharedCharts.apply(chart,received);
        assertSame(route,chart.routes.getFirst());assertEquals(24,route.performance.approachSpeed);assertEquals(0,route.performance.ilsFrequency);
        assertThrows(IllegalArgumentException.class,()->RadioNavigation.createStation(chart,Kind.ILS,0,0,0));
    }
}
