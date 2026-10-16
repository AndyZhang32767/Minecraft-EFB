package dev.airac.nav;

import dev.airac.data.*;
import dev.airac.data.ChartData.*;
import dev.airac.sync.SharedCharts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AirwayEditorTest {
    @TempDir Path root;
    ChartData chart=new ChartData();
    List<Point> points=new ArrayList<>();
    Airway airway=new Airway();
    AirwayEditorTest(){
        for(int i=0;i<6;i++)points.add(new Point("P"+i,Kind.WAYPOINT,i*100,0,0));
        chart.points.addAll(points);airway.name="A1";
        airway.pointIds.addAll(List.of(id(1),id(2),id(3)));chart.airways.add(airway);
    }
    String id(int i){return points.get(i).id;}
    @Test void headAndTailInsertionFollowClickOrderAndRetainDirection() {
        airway.oneWay=true;
        int cursor=AirwayEditor.insert(chart,airway,0,id(0));
        assertEquals(1,cursor);assertEquals(-2,AirwayEditor.insert(chart,airway,cursor,id(1)));
        assertEquals(-1,AirwayEditor.insert(chart,airway,-1,id(4)));
        assertEquals(List.of(id(0),id(1),id(2),id(3),id(4)),RoutePlanner.airwayPath(chart,"A1",id(0),id(4)));
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airwayPath(chart,"A1",id(4),id(0)));
        chart.validate();
    }
    @Test void deletingAReferenceRetainsGlobalFixAndBlocksRoutingUntilDownstreamJoin() {
        AirwayEditor.remove(airway,1);assertNotNull(chart.point(id(2)));assertTrue(airway.disconnectedBefore(1));
        assertTrue(chart.mapConnectedPoints().isEmpty());
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airwayPath(chart,"A1",id(1),id(3)));
        int cursor=AirwayEditor.insert(chart,airway,1,id(4));
        cursor=AirwayEditor.insert(chart,airway,cursor,id(5));
        assertTrue(airway.disconnectedBefore(3));
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airwayPath(chart,"A1",id(1),id(3)));
        assertEquals(-2,AirwayEditor.insert(chart,airway,cursor,id(3)));
        assertEquals(List.of(id(1),id(4),id(5),id(3)),RoutePlanner.airwayPath(chart,"A1",id(1),id(3)));
        chart.validate();
    }
    @Test void connectGapAndResumeTailUseExistingVerticesWithoutDuplicatingThem() {
        AirwayEditor.remove(airway,1);AirwayEditor.connect(airway,1);
        assertEquals(List.of(id(1),id(3)),RoutePlanner.airwayPath(chart,"A1",id(1),id(3)));
        AirwayEditor.insert(chart,airway,-1,id(4));AirwayEditor.insert(chart,airway,-1,id(4));
        assertEquals(List.of(id(1),id(3),id(4)),airway.pointIds);
        assertThrows(IllegalArgumentException.class,()->AirwayEditor.insert(chart,airway,-2,id(5)));
    }
    @Test void gapsPersistAcrossDiskAndNetworkWithoutChangingPersonalPlan() throws Exception {
        Route route=new Route();route.pointIds.addAll(airway.pointIds);chart.routes.add(route);chart.activate(route);chart.activeLeg=1;
        AirwayEditor.remove(airway,1);var store=new ChartStore(root);store.save("w","d",chart);
        var loaded=store.load("w","d","world");assertTrue(loaded.airways.getFirst().disconnectedBefore(1));
        var shared=SharedCharts.decode(SharedCharts.encode(chart));assertTrue(shared.airways.getFirst().disconnectedBefore(1));
        SharedCharts.apply(chart,shared);assertSame(route,chart.activeRoute());assertEquals(1,chart.activeLeg);
        assertEquals(List.of(id(1),id(2),id(3)),route.pointIds);
    }
    @Test void deletingAGlobalFixDoesNotReconnectAnAlreadyBrokenAirway() {
        AirwayEditor.insert(chart,airway,-1,id(4));AirwayEditor.insert(chart,airway,-1,id(5));
        airway.discontinuities.set(2,true);chart.removePoint(id(5));
        assertThrows(IllegalArgumentException.class,()->RoutePlanner.airwayPath(chart,"A1",id(1),id(4)));
        chart.validate();
    }
}
