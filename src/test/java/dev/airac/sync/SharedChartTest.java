package dev.airac.sync;

import dev.airac.data.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedChartTest {
    @TempDir Path root;
    private ChartData chart() {
        var c=new ChartData();
        c.points.add(new ChartData.Point("APT",ChartData.Kind.AIRPORT,0,100,0));
        c.points.add(new ChartData.Point("FIX",ChartData.Kind.WAYPOINT,100,150,200));
        var runway=new ChartData.Runway();runway.airportId=c.points.getFirst().id;runway.x2=100;c.runways.add(runway);
        var airway=new ChartData.Airway();airway.pointIds.addAll(List.of(c.points.getFirst().id,c.points.getLast().id));c.airways.add(airway);
        return c;
    }
    @Test void airportAndNavigationIdentifiersAreSeparateAndLookupIsContextual() throws Exception {
        for (var kind : List.of(ChartData.Kind.WAYPOINT,ChartData.Kind.NDB,ChartData.Kind.VOR)) {
            var c=new ChartData();var airport=new ChartData.Point("SAME",ChartData.Kind.AIRPORT,0,64,0);
            c.points.add(airport);
            var nav=kind==ChartData.Kind.WAYPOINT ? new ChartData.Point("same",kind,100,0,0)
                    : dev.airac.nav.RadioNavigation.createStation(c,kind,100,64,0);
            nav.ident="same";c.points.add(nav);
            assertFalse(c.identifierUsed("SAME",kind,nav.id));
            assertFalse(c.identifierUsed("same",ChartData.Kind.AIRPORT,airport.id));
            assertTrue(c.identifierUsed("SAME",kind,null));
            assertSame(airport,dev.airac.nav.RoutePlanner.airportByIdent(c,"same"));
            assertSame(nav,dev.airac.nav.RoutePlanner.byIdent(c,"SAME"));
            Collections.reverse(c.points);
            assertSame(airport,dev.airac.nav.RoutePlanner.airportByIdent(c,"same"));
            assertSame(nav,dev.airac.nav.RoutePlanner.byIdent(c,"SAME"));
            var repository=new SharedChartRepository(root.resolve(kind.name()));
            assertEquals(SharedChartRepository.Result.OK,repository.update("d",0,SharedCharts.encode(c),true));
            assertEquals(2,new SharedChartRepository(root.resolve(kind.name())).get("d").chart.points.size());
            c.points.add(new ChartData.Point("SAME",ChartData.Kind.WAYPOINT,200,0,0));
            assertThrows(IllegalArgumentException.class,()->SharedCharts.encode(c));
        }
    }
    @Test void operatorCommitsPersistAcrossRestartAndDimensionsStaySeparate() throws Exception {
        var repository=new SharedChartRepository(root);var c=chart();
        assertEquals(SharedChartRepository.Result.OK,repository.update("minecraft:overworld",0,SharedCharts.encode(c),true));
        var reopened=new SharedChartRepository(root);
        assertEquals(repository.serverId,reopened.serverId);
        assertEquals(c.points.getFirst().id,reopened.get("minecraft:overworld").chart.points.getFirst().id);
        assertTrue(reopened.get("minecraft:the_nether").chart.points.isEmpty());
    }
    @Test void unauthorizedAndStaleConcurrentWritesCannotOverwriteNewerChart() throws Exception {
        var repository=new SharedChartRepository(root);var first=chart();var second=chart();
        assertEquals(SharedChartRepository.Result.FORBIDDEN,repository.update("d",0,SharedCharts.encode(first),false));
        assertEquals(SharedChartRepository.Result.OK,repository.update("d",0,SharedCharts.encode(first),true));
        assertEquals(SharedChartRepository.Result.CONFLICT,repository.update("d",0,SharedCharts.encode(second),true));
        assertEquals(first.points.getFirst().id,repository.get("d").chart.points.getFirst().id);
    }
    @Test void snapshotsNeverUploadOrReplacePersonalFlightPlans() {
        var personal=chart();var plan=new ChartData.Route();
        plan.pointIds.add(personal.points.getFirst().id);plan.pointIds.add(personal.points.getLast().id);
        personal.routes.add(plan);personal.activate(plan);personal.activeLeg=1;
        var shared=SharedCharts.decode(SharedCharts.encode(personal));
        assertTrue(shared.routes.isEmpty());assertNull(shared.activeRouteId);assertEquals(0,shared.activeLeg);
        shared.points.getLast().x=999;
        SharedCharts.apply(personal,shared);
        assertSame(plan,personal.activeRoute());assertEquals(1,personal.activeLeg);assertEquals(999,personal.target().x);
        shared.removePoint(shared.points.getFirst().id);SharedCharts.apply(personal,shared);
        assertEquals(0,personal.activeLeg);assertEquals("FIX",personal.target().ident);
    }
    @Test void programEditsSyncButInvalidateOnlyEachPlayersOwnSelection() {
        var alice=chart();var arrival=new ChartData.Point("DEST",ChartData.Kind.AIRPORT,400,64,0);alice.points.add(arrival);
        var r=alice.runways.getFirst();r.name="27L/09R";
        var p=new ChartData.Procedure();p.name="SID1";p.airportId=r.airportId;p.runwayId=r.id;p.runwayEnd="27L";
        p.pointIds.add(alice.points.get(1).id);alice.procedures.add(p);alice.airways.getFirst().oneWay=true;
        var bob=SharedCharts.decode(SharedCharts.encode(alice));
        assertEquals("27L",bob.procedures.getFirst().runwayEnd);assertTrue(bob.airways.getFirst().oneWay);
        var plan=new ChartData.Route();plan.name="PRIVATE";alice.routes.add(plan);
        dev.airac.nav.RoutePlanner.endpoints(alice,plan,alice.points.getFirst(),arrival);
        dev.airac.nav.RoutePlanner.terminal(alice,plan,true,r.id,"09R",p.id);
        bob.procedures.getFirst().name="SID2";
        SharedCharts.apply(alice,bob);
        assertSame(plan,alice.activeRoute());assertNull(plan.departureProcedureId);assertFalse(plan.departureConfigured);
        assertEquals(List.of(ChartData.thresholdId(r.id,"09R")),plan.pointIds);assertFalse(plan.disconnectedBefore(0));
        assertTrue(bob.routes.isEmpty());assertFalse(new String(SharedCharts.encode(alice)).contains("PRIVATE"));alice.validate();
    }
    @Test void invalidGeometryAndFlightPlanInjectionAreRejected() throws Exception {
        var repository=new SharedChartRepository(root);
        assertEquals(SharedChartRepository.Result.INVALID,repository.update("d",0,"{broken".getBytes(),true));
        var c=chart();c.points.getFirst().x=Double.NaN;
        assertThrows(IllegalArgumentException.class,()->SharedCharts.encode(c));
        var c2=chart();c2.points.add(new ChartData.Point("fix",ChartData.Kind.WAYPOINT,200,0,0));
        assertThrows(IllegalArgumentException.class,()->SharedCharts.encode(c2));
        assertThrows(IllegalArgumentException.class,()->SharedCharts.decode("{\"routes\":[{\"id\":\"x\",\"name\":\"x\",\"pointIds\":[]}]}".getBytes()));
        assertEquals(0,repository.get("d").revision);
    }
    @Test void mcduPlanMetadataAndProgressStayLocalAcrossTwoClients() {
        var alice=chart();var bob=SharedCharts.copy(alice);
        var arrival=new ChartData.Point("DEST",ChartData.Kind.AIRPORT,400,64,0);alice.points.add(arrival);
        SharedCharts.apply(bob,SharedCharts.decode(SharedCharts.encode(alice)));
        var plan=new ChartData.Route();plan.name="ALICE PRIVATE";alice.routes.add(plan);
        dev.airac.nav.RoutePlanner.endpoints(alice,plan,alice.points.getFirst(),arrival);
        dev.airac.nav.RoutePlanner.direct(alice,plan,-1,alice.points.get(1).id);
        alice.activeLeg=1;
        String packet=new String(SharedCharts.encode(alice),java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(packet.contains("ALICE PRIVATE"));assertFalse(packet.contains("departureId"));assertFalse(packet.contains("arrivalId"));
        SharedCharts.apply(bob,SharedCharts.decode(SharedCharts.encode(alice)));assertTrue(bob.routes.isEmpty());
        SharedCharts.apply(alice,SharedCharts.decode(SharedCharts.encode(bob)));
        assertSame(plan,alice.activeRoute());assertEquals(1,alice.activeLeg);assertEquals(arrival.id,plan.arrivalId);
        assertThrows(IllegalArgumentException.class,()->SharedCharts.decode("{\"activeRouteId\":\"private\",\"activeLeg\":3}".getBytes()));
    }
    @Test void corruptServerStorageFailsClosedAndPreservesOriginal() throws Exception {
        var store=new ChartStore(root);var path=store.directory("shared","d").resolve("chart.json");
        Files.createDirectories(path.getParent());Files.writeString(path,"{broken");
        var repository=new SharedChartRepository(root);
        assertFalse(repository.get("d").writable);
        assertEquals(SharedChartRepository.Result.READ_ONLY,repository.update("d",0,SharedCharts.encode(chart()),true));
        assertEquals("{broken",Files.readString(path));
    }
    @Test void saveFailureDoesNotPublishAnUnsavedRevision() throws Exception {
        var repository=new SharedChartRepository(root);repository.get("d");
        Files.writeString(root.resolve("charts"),"blocked");
        assertEquals(SharedChartRepository.Result.SAVE_FAILED,repository.update("d",0,SharedCharts.encode(chart()),true));
        assertTrue(repository.get("d").chart.points.isEmpty());assertEquals(0,repository.get("d").revision);
    }
    @Test void transferAcceptsMultipleChunksAndRejectsReorderingOversizeAndTimeout() {
        var assembly=new ChunkAssembly();UUID id=UUID.randomUUID();
        byte[] first=new byte[ChunkAssembly.CHUNK_BYTES];Arrays.fill(first,(byte)42);
        assertNull(assembly.accept(id,0,2,first,0));
        byte[] result=assembly.accept(id,1,2,new byte[]{7},1);
        assertEquals(first.length+1,result.length);assertEquals(7,result[result.length-1]);
        assertThrows(IllegalArgumentException.class,()->assembly.accept(id,1,2,first,2));
        assertThrows(IllegalArgumentException.class,()->assembly.accept(id,0,100,first,2));
        assertThrows(IllegalArgumentException.class,()->assembly.accept(id,0,1,new byte[ChunkAssembly.CHUNK_BYTES+1],2));
        assembly.accept(id,0,2,first,0);
        assertThrows(IllegalArgumentException.class,()->assembly.accept(id,1,2,first,10_001));
    }
    @Test void serverIdentityChangesForNewWorldAtSameAddress() throws Exception {
        assertNotEquals(new SharedChartRepository(root.resolve("a")).serverId,new SharedChartRepository(root.resolve("b")).serverId);
    }
}
