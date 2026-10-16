package dev.airac.nav;

import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutePlannerTest {
    ChartData data; Point dep,a,b,c,arr; Route route; String depAnchor,arrAnchor;
    @BeforeEach void setup() {
        data=new ChartData();
        dep=new Point("DEP",Kind.AIRPORT,0,64,0);arr=new Point("ARR",Kind.AIRPORT,500,70,0);
        a=new Point("A",Kind.WAYPOINT,100,0,0);b=new Point("B",Kind.WAYPOINT,200,0,0);c=new Point("C",Kind.WAYPOINT,300,0,0);
        data.points.addAll(List.of(dep,a,b,c,arr));
        Airway airway=new Airway();airway.name="J1";airway.pointIds.addAll(List.of(a.id,b.id,c.id));data.airways.add(airway);
        route=new Route();data.routes.add(route);RoutePlanner.endpoints(data,route,dep,arr);
        Runway d=runway(dep),r=runway(arr);
        RoutePlanner.terminal(data,route,true,d.id,"27L",null);
        RoutePlanner.terminal(data,route,false,r.id,"09R",null);
        depAnchor=route.pointIds.getFirst();arrAnchor=route.pointIds.getLast();
    }
    @Test void airwaysExpandAllIntermediateFixesAndUseDirToEnterAndLeave() {
        int selected=RoutePlanner.airway(data,route,0,a.id,"j1",c.id);
        assertEquals(3,selected);
        assertEquals(List.of(depAnchor,a.id,b.id,c.id,arrAnchor),route.pointIds);
        assertEquals(List.of("DIR","DIR","J1","J1","DIR"),route.vias);data.validate();
    }
    @Test void joinsDoNotDuplicateEntryAndCanTraverseInReverse() {
        RoutePlanner.direct(data,route,0,c.id);
        RoutePlanner.airway(data,route,1,c.id,"J1",a.id);
        assertEquals(List.of(depAnchor,c.id,b.id,a.id,arrAnchor),route.pointIds);data.validate();
    }
    @Test void sameNamedLegacySegmentsConnectButDifferentNamesDoNot() {
        data.airways.getFirst().pointIds.removeLast();
        Airway second=new Airway();second.name="J1";second.pointIds.addAll(List.of(b.id,c.id));data.airways.add(second);
        assertEquals(List.of(a.id,b.id,c.id),RoutePlanner.airwayPath(data,"J1",a.id,c.id));
        second.name="J2";
        var before=new ArrayList<>(route.pointIds);
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airway(data,route,0,a.id,"J1",c.id));
        assertEquals(before,route.pointIds);assertEquals(List.of("DIR","DIR"),route.vias);
    }
    @Test void insertionDeletionAndReorderingInvalidateAffectedAirwayLabels() {
        RoutePlanner.airway(data,route,0,a.id,"J1",c.id);
        RoutePlanner.direct(data,route,2,dep.id);
        assertEquals("DIR",route.vias.get(3));assertEquals("DIR",route.vias.get(4));
        RoutePlanner.remove(data,route,3);
        RoutePlanner.move(data,route,2,1);
        assertEquals(List.of(depAnchor,a.id,c.id,b.id,arrAnchor),route.pointIds);
        assertEquals(List.of("DIR","DIR","DIR","DIR","DIR"),route.vias);data.validate();
    }
    @Test void cannotReorderOrRemoveDepArrAndDirectInsertsBeforeArrival() {
        RoutePlanner.direct(data,route,99,a.id);
        RoutePlanner.move(data,route,1,-1);RoutePlanner.remove(data,route,0);RoutePlanner.remove(data,route,2);
        assertEquals(List.of(depAnchor,a.id,arrAnchor),route.pointIds);data.validate();
    }
    @Test void changingEndpointsPreservesEnrouteFixesAndPlansAreIndependent() {
        RoutePlanner.airway(data,route,0,a.id,"J1",c.id);
        Route other=new Route();data.routes.add(other);RoutePlanner.endpoints(data,other,arr,dep);
        RoutePlanner.endpoints(data,route,arr,dep);
        RoutePlanner.remove(data,route,1);
        assertTrue(other.pointIds.isEmpty());
        assertEquals(List.of(a.id,c.id),route.pointIds);data.validate();
    }
    @Test void deletingSharedFixSplitsAirwayAndKeepsPersonalViaAlignment() {
        RoutePlanner.airway(data,route,0,a.id,"J1",c.id);
        data.activeLeg=3;data.removePoint(b.id);
        assertTrue(data.airways.isEmpty());assertNull(data.target());assertEquals(2,data.activeLeg);
        assertTrue(route.disconnectedBefore(2));assertEquals("DIR",route.vias.get(2));data.validate();
        RoutePlanner.connectGap(data,route,2);data.activeLeg=2;assertEquals(c.id,data.target().id);
    }
    @Test void deletingOnePointPreservesUnrelatedRemainingAirwaySegments() {
        data.airways.getFirst().pointIds.add(arr.id);
        data.removePoint(b.id);
        assertEquals(1,data.airways.size());assertEquals(List.of(c.id,arr.id),data.airways.getFirst().pointIds);data.validate();
    }
    @Test void cyclicNetworkTerminatesAndMissingEndpointsDoNotChangePlan() {
        data.airways.getFirst().pointIds.add(a.id);
        assertEquals(List.of(a.id,c.id),RoutePlanner.airwayPath(data,"J1",a.id,c.id));
        route.clear();
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airway(data,route,0,a.id,"J1",c.id));
        assertTrue(route.pointIds.isEmpty());
    }

    @Test void routeMapCanBuildWithoutInitAndKeepsPlansIndependent() {
        Route plain=new Route();data.routes.add(plain);
        RoutePlanner.direct(data,plain,-1,a.id);RoutePlanner.direct(data,plain,-1,b.id);
        assertEquals(List.of(a.id,b.id),plain.pointIds);
        assertEquals(List.of("DIR","DIR"),plain.vias);
        assertEquals(List.of(depAnchor,arrAnchor),route.pointIds);data.validate();
    }
    @Test void initReusesMatchingMapRouteEndpoints() {
        Route plain=new Route();data.routes.add(plain);
        for(Point p:List.of(dep,a,b,arr))RoutePlanner.direct(data,plain,-1,p.id);
        RoutePlanner.endpoints(data,plain,dep,arr);
        assertEquals(List.of(a.id,b.id),plain.pointIds);
        assertFalse(plain.disconnectedBefore(1));data.validate();
    }
    Runway runway(Point airport) {
        Runway r=new Runway();r.airportId=airport.id;r.name="27L/09R";r.x2=100;data.runways.add(r);return r;
    }
    Procedure program(Point airport, Runway runway, Point... points) {
        Procedure p=new Procedure();p.airportId=airport.id;p.runwayId=runway.id;p.runwayEnd="09R";p.name="TEST1";
        p.pointIds.addAll(Arrays.stream(points).map(q->q.id).toList().reversed());data.procedures.add(p);return p;
    }
    @Test void oneWayFollowsFixOrderAndRejectsReverseWithoutMutatingPlan() {
        data.airways.getFirst().oneWay=true;
        RoutePlanner.direct(data,route,-1,a.id);
        RoutePlanner.airway(data,route,-1,a.id,"J1",c.id);
        assertEquals(List.of(depAnchor,a.id,b.id,c.id,arrAnchor),route.pointIds);
        var before=new ArrayList<>(route.pointIds);var gaps=new ArrayList<>(route.discontinuities);
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airway(data,route,-1,c.id,"J1",a.id));
        assertEquals(before,route.pointIds);assertEquals(gaps,route.discontinuities);
    }
    @Test void deletingFixPreservesOneWayOnSurvivingSegments() {
        data.airways.getFirst().oneWay=true;data.airways.getFirst().pointIds.add(arr.id);
        data.removePoint(b.id);assertTrue(data.airways.getFirst().oneWay);
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airwayPath(data,"J1",arr.id,c.id));
    }
    @Test void starIsInboundAndSidIsReversedWithFullRunwaySuffix() {
        Runway d=runway(dep),r=runway(arr);Procedure sid=program(dep,d,a,b),star=program(arr,r,c,b);
        assertEquals(List.of("27L","09R"),d.ends());
        assertEquals("09R",RoutePlanner.procedureRunwayEnd(data,star,false));
        RoutePlanner.terminal(data,route,true,d.id,"27L",sid.id);depAnchor=ChartData.thresholdId(d.id,"27L");
        RoutePlanner.terminal(data,route,false,r.id,"09R",star.id);arrAnchor=ChartData.thresholdId(r.id,"09R");
        assertEquals(List.of(depAnchor,a.id,b.id,c.id,arrAnchor),route.pointIds);
        assertEquals(List.of("DEP","SID","SID","STAR","ARR"),route.sections);
        assertFalse(route.disconnectedBefore(3));assertFalse(route.disconnectedBefore(4));data.validate();
    }
    @Test void terminalSelectionIsAtomicAndCannotUseWrongAirportOrRunwayEnd() {
        Runway d=runway(dep);Procedure p=program(dep,d,a,b);
        var before=new ArrayList<>(route.pointIds);
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.terminal(data,route,false,d.id,"09R",p.id));
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.terminal(data,route,true,d.id,"09R",p.id));
        assertEquals(before,route.pointIds);assertNotNull(route.departureRunwayId);assertTrue(route.departureConfigured);
    }
    @Test void sharedSidExitRetainsValidFollowingAirwayLeg() {
        RoutePlanner.direct(data,route,-1,b.id);
        RoutePlanner.airway(data,route,-1,b.id,"J1",c.id);
        Runway d=runway(dep);Procedure sid=program(dep,d,a,b);
        RoutePlanner.terminal(data,route,true,d.id,"27L",sid.id);depAnchor=ChartData.thresholdId(d.id,"27L");
        assertEquals(List.of(depAnchor,a.id,b.id,c.id,arrAnchor),route.pointIds);
        assertFalse(route.disconnectedBefore(2));assertFalse(route.disconnectedBefore(3));
        assertEquals("J1",route.vias.get(3));data.validate();
    }
    @Test void gapInsertionJoinsExistingDownstreamFixAndTailButtonRestoresAppend() {
        RoutePlanner.direct(data,route,-1,a.id);RoutePlanner.airway(data,route,-1,a.id,"J1",c.id);
        RoutePlanner.remove(data,route,2);assertTrue(route.disconnectedBefore(2));
        RoutePlanner.beginGap(route,2);
        RoutePlanner.airway(data,route,route.editAfter,a.id,"J1",c.id);
        assertEquals(List.of(depAnchor,a.id,b.id,c.id,arrAnchor),route.pointIds);
        assertFalse(route.disconnectedBefore(2));assertFalse(route.disconnectedBefore(3));
        assertEquals(3,route.editAfter);RoutePlanner.appendAtEnd(route);
        assertEquals(-1,route.editAfter);assertEquals(4,RoutePlanner.editIndex(route));data.validate();
    }
    @Test void deletingAirwayBlocksTrackerUntilExplicitDirConnection() {
        RoutePlanner.direct(data,route,-1,a.id);RoutePlanner.airway(data,route,-1,a.id,"J1",c.id);
        RoutePlanner.connectGap(data,route,4);
        RoutePlanner.removeAirway(data,route,2);
        assertEquals(List.of(depAnchor,a.id,arrAnchor),route.pointIds);assertTrue(route.disconnectedBefore(2));
        data.activeLeg=2;var tracker=new RouteTracker();
        assertFalse(tracker.tick(data,arr.x,arr.z,40));assertEquals(2,data.activeLeg);
        RoutePlanner.connectGap(data,route,2);data.activeLeg=2;
        Point threshold=data.point(arrAnchor);assertTrue(tracker.tick(data,threshold.x,threshold.z,40));
        assertFalse(route.pointIds.contains(arrAnchor));assertNull(data.target());
    }
    @Test void editedOrDeletedTerminalProgramClearsOldFixesAndLeavesGap() {
        Runway d=runway(dep);Procedure sid=program(dep,d,a,b);
        RoutePlanner.terminal(data,route,true,d.id,"27L",sid.id);depAnchor=ChartData.thresholdId(d.id,"27L");RoutePlanner.direct(data,route,-1,c.id);
        data.removeProcedure(sid.id);
        assertEquals(List.of(depAnchor,c.id,arrAnchor),route.pointIds);
        assertTrue(route.disconnectedBefore(1));assertNull(route.departureProcedureId);assertFalse(route.departureConfigured);data.validate();
    }

    @Test void initAirportsAreMetadataUntilRunwaysAreSelected() {
        Route plain=new Route();data.routes.add(plain);
        RoutePlanner.endpoints(data,plain,dep,arr);
        assertTrue(plain.pointIds.isEmpty());assertNull(data.target());
        RoutePlanner.direct(data,plain,-1,a.id);
        assertEquals(List.of(a.id),plain.pointIds);
        Runway d=runway(dep);d.x1=25.5;d.z1=-12.5;d.x2=80.5;d.z2=34.5;d.elevation=72;
        RoutePlanner.terminal(data,plain,true,d.id,"09R",null);
        Point first=data.point(plain.pointIds.getFirst());
        assertEquals(80.5,first.x);assertEquals(34.5,first.z);assertEquals(72,first.y);
        assertEquals("DEP/09R",first.ident);assertFalse(data.points.contains(first));
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.terminal(data,plain,false,d.id,"27L",null));
        Runway r=runway(arr);r.x1=420.5;r.z1=18.5;
        RoutePlanner.terminal(data,plain,false,r.id,"27L",null);
        Point last=data.point(plain.pointIds.getLast());assertEquals(420.5,last.x);assertEquals(18.5,last.z);
        assertEquals(arr.id,plain.arrivalId);data.validate();
    }
    @Test void clearingOrDeletingRunwayRemovesAnchorButPreservesEnrouteWork() {
        RoutePlanner.direct(data,route,-1,a.id);
        RoutePlanner.terminal(data,route,true,null,"",null);
        assertEquals(List.of(a.id,arrAnchor),route.pointIds);
        assertTrue(RoutePlanner.editableLeg(route,0));
        data.removeRunway(route.arrivalRunwayId);
        assertEquals(List.of(a.id),route.pointIds);assertNull(route.arrivalRunwayId);data.validate();
    }
    @Test void emptyInitCanExpandAirwayWithoutInventingAirportWaypoints() {
        Route empty=new Route();data.routes.add(empty);RoutePlanner.endpoints(data,empty,dep,arr);
        RoutePlanner.airway(data,empty,-1,a.id,"J1",c.id);
        assertEquals(List.of(a.id,b.id,c.id),empty.pointIds);
        assertFalse(empty.disconnectedBefore(0));data.validate();
    }

    @Test void circuitCanSelectSameRunwayForDepartureAndArrivalIndependently() {
        Route circuit=new Route();data.routes.add(circuit);RoutePlanner.endpoints(data,circuit,dep,dep);
        Runway rw=runway(dep);
        RoutePlanner.terminal(data,circuit,true,rw.id,"27L",null);
        RoutePlanner.terminal(data,circuit,false,rw.id,"27L",null);
        RoutePlanner.terminal(data,circuit,true,rw.id,"09R",null);
        assertEquals(List.of(ChartData.thresholdId(rw.id,"09R"),ChartData.thresholdId(rw.id,"27L")),circuit.pointIds);
        RoutePlanner.terminal(data,circuit,false,rw.id,"09R",null);
        assertEquals(2,circuit.pointIds.size());assertEquals(List.of("DEP","ARR"),circuit.sections);data.validate();
    }
}
