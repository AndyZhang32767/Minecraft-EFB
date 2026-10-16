package dev.airac.data;

import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChartDataTest {
    ChartData chart;
    ChartData.Point a,b,c;
    ChartData.Route plan;
    @BeforeEach void setup() {
        chart=new ChartData();
        a=new ChartData.Point("APT",ChartData.Kind.AIRPORT,0,64,0);
        b=new ChartData.Point("FIX1",ChartData.Kind.WAYPOINT,100,64,0);
        c=new ChartData.Point("FIX2",ChartData.Kind.WAYPOINT,200,64,0);
        chart.points.addAll(List.of(a,b,c));
        var airway=new ChartData.Airway();airway.pointIds.addAll(java.util.List.of(a.id,b.id));airway.name="A1";chart.airways.add(airway);
        plan=new ChartData.Route();plan.name="Test";plan.pointIds.addAll(List.of(a.id,b.id,c.id));chart.routes.add(plan);chart.activate(plan);
    }
    @Test void clearingPlansPreservesAirwayNetworkAndPoints() {
        chart.clearRoutes();assertEquals(1,chart.airways.size());assertEquals(3,chart.points.size());
        assertNull(chart.activeRoute());assertNull(chart.target());chart.validate();
    }
    @Test void clearingAirwaysPreservesCurrentPlanAndLeg() {
        chart.activeLeg=1;chart.clearAirways();assertSame(plan,chart.activeRoute());assertSame(b,chart.target());chart.validate();
    }
    @Test void editingAirwayNeverChangesNdTarget() {
        chart.airways.getFirst().pointIds.set(1,c.id);assertSame(a,chart.target());assertEquals(List.of(a.id,b.id,c.id),plan.pointIds);
    }
    @Test void editingActivePlanUpdatesTargetAndCanResetAllLegs() {
        plan.pointIds.set(0,c.id);chart.routeChanged(plan);assertSame(c,chart.target());
        plan.pointIds.clear();chart.routeChanged(plan);chart.validate();assertNull(chart.target());assertEquals(1,chart.airways.size());
    }
    @Test void deletingSharedPointCascadesAndPreservesCurrentTarget() {
        chart.activeLeg=2;chart.removePoint(a.id);assertSame(c,chart.target());assertEquals(1,chart.activeLeg);
        assertTrue(chart.airways.isEmpty());assertEquals(List.of(b.id,c.id),plan.pointIds);chart.validate();
    }
    @Test void deletingCurrentPointBlocksNextLeg() {
        chart.activeLeg=1;chart.removePoint(b.id);assertNull(chart.target());assertTrue(plan.disconnectedBefore(1));chart.validate();
    }
    @Test void deletingAirportDeletesItsRunways() {
        var r=new ChartData.Runway();r.airportId=a.id;r.x2=100;chart.runways.add(r);chart.removePoint(a.id);assertTrue(chart.runways.isEmpty());
    }
    @Test void deletingRepeatedPointPreservesLeg() {
        plan.pointIds.clear();plan.pointIds.addAll(List.of(a.id,b.id,a.id,c.id));chart.activeLeg=3;
        chart.removePoint(a.id);assertEquals(List.of(b.id,c.id),plan.pointIds);assertEquals(1,chart.activeLeg);assertNull(chart.target());assertTrue(plan.disconnectedBefore(1));
    }
    @Test void blockCentersRespectNegativeBlocks() {
        assertEquals(10.5,ChartData.blockCenter(10));assertEquals(-0.5,ChartData.blockCenter(-0.1));
        assertEquals(-10.5,ChartData.blockCenter(-10.8));assertEquals(10.5,ChartData.blockCenter(10.9));
    }
    @Test void zoomDeclutteringExcludesProgramsAndDisconnectedPlanFixes() {
        chart.clearAirways();plan.normalizeVias();
        var program=new ChartData.Procedure();program.airportId=a.id;program.name="SID1";program.pointIds.addAll(List.of(c.id,b.id));
        chart.procedures.add(program);plan.departureProcedureId=program.id;
        plan.vias.set(1,"SID1");plan.vias.set(2,"SID1");plan.sections.set(1,"SID");plan.sections.set(2,"SID");
        assertTrue(chart.mapConnectedPoints().isEmpty());
        // An explicit ordinary DIR leg counts even if a fix was inserted by a procedure.
        plan.vias.set(2,"DIR");assertEquals(java.util.Set.of(b.id,c.id),chart.mapConnectedPoints());
        plan.discontinuities.set(2,true);assertTrue(chart.mapConnectedPoints().isEmpty());
    }
    @Test void starEntryAndSidExitCountWhenJoinedByOrdinaryLegs() {
        chart.clearAirways();plan.normalizeVias();
        var sid=new ChartData.Procedure();sid.airportId=a.id;sid.name="SID1";sid.pointIds.add(b.id);chart.procedures.add(sid);
        plan.departureProcedureId=sid.id;plan.vias.set(1,"SID1");plan.sections.set(1,"SID");
        assertEquals(java.util.Set.of(b.id,c.id),chart.mapConnectedPoints());
        // Reverse the same stored outbound program for STAR; retain the STAR-owned entry.
        plan.departureProcedureId=null;plan.arrivalProcedureId=sid.id;
        plan.pointIds=new java.util.ArrayList<>(List.of(c.id,b.id,a.id));
        plan.vias=new java.util.ArrayList<>(List.of("DIR","DIR","SID1"));
        plan.sections=new java.util.ArrayList<>(List.of("","STAR",""));
        assertEquals(java.util.Set.of(b.id,c.id),chart.mapConnectedPoints());
        plan.discontinuities.set(1,true);assertTrue(chart.mapConnectedPoints().isEmpty());
        // Unselected personal plans must not keep map points visible.
        chart.deactivate();assertTrue(chart.mapConnectedPoints().isEmpty());
    }
    @Test void gapBeforeFirstFixIsRejected() {
        plan.normalizeVias();plan.discontinuities.set(0,true);
        assertThrows(IllegalArgumentException.class,chart::validate);
    }
    @Test void invalidGeometryAndDanglingRoutesAreRejected() {
        a.x=Double.NaN;assertThrows(IllegalArgumentException.class,chart::validate);a.x=0;
        plan.pointIds.add("missing");assertThrows(IllegalArgumentException.class,chart::validate);
    }
    @Test void completedPlanStaysCompletedAfterValidation() {
        chart.activeLeg=3;chart.validate();assertNull(chart.target());assertSame(plan,chart.activeRoute());
    }
}
