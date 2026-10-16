package dev.airac.data;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ChartStoreTest {
    @TempDir Path root;
    @Test void worldsServersAndDimensionsHaveIsolatedData() throws IOException {
        var store=new ChartStore(root);var chart=new ChartData();
        chart.points.add(new ChartData.Point("A",ChartData.Kind.WAYPOINT,10,70,-25));
        store.save("singleplayer:C:/saves/one","minecraft:overworld",chart);
        assertEquals(1,store.load("singleplayer:C:/saves/one","minecraft:overworld","one").points.size());
        assertTrue(store.load("singleplayer:C:/saves/two","minecraft:overworld","two").points.isEmpty());
        assertTrue(store.load("singleplayer:C:/saves/one","minecraft:the_nether","one").points.isEmpty());
        assertTrue(store.load("server:example.org","minecraft:overworld","server").points.isEmpty());
    }
    @Test void chartAndIndependentSystemsRoundTrip() throws IOException {
        var store=new ChartStore(root);var c=new ChartData();
        var a=new ChartData.Point("A",ChartData.Kind.AIRPORT,-100,70,5);
        var b=new ChartData.Point("B",ChartData.Kind.WAYPOINT,300,70,-200);
        c.points.add(a);c.points.add(b);
        var airway=new ChartData.Airway();airway.pointIds.addAll(java.util.List.of(a.id,b.id));c.airways.add(airway);
        var plan=new ChartData.Route();plan.pointIds.add(b.id);plan.pointIds.add(a.id);c.routes.add(plan);c.activate(plan);c.activeLeg=1;
        store.save("server:a","dim",c);var loaded=store.load("server:a","dim","A");
        assertEquals(a.id,loaded.target().id);assertEquals(a.id,loaded.airways.getFirst().pointIds.getFirst());
        assertEquals(b.id,loaded.routes.getFirst().pointIds.getFirst());
    }
    @Test void writesKeepPreviousRevisionAndLeaveNoTemporaryFiles() throws IOException {
        var store=new ChartStore(root);var c=new ChartData();c.worldName="before";store.save("w","d",c);
        c.worldName="after";store.save("w","d",c);
        assertTrue(Files.readString(store.directory("w","d").resolve("chart.json.bak")).contains("before"));
        try(var paths=Files.walk(root)){assertEquals(0,paths.filter(p->p.toString().endsWith(".tmp")).count());}
    }
    @Test void corruptAndFutureFilesAreNotSilentlyReplaced() throws IOException {
        var store=new ChartStore(root);Path path=store.directory("w","d").resolve("chart.json");Files.createDirectories(path.getParent());
        Files.writeString(path,"{ broken");
        assertThrows(IOException.class,()->store.load("w","d","world"));assertEquals("{ broken",Files.readString(path));
        Files.writeString(path,"{\"schemaVersion\":100}");assertThrows(IOException.class,()->store.load("w","d","world"));
    }
    @Test void legacyNavigationMigratesAutomaticallyAndKeepsPermanentUpgradeBackup() throws IOException {
        var store=new ChartStore(root);Path path=store.directory("legacy","d").resolve("chart.json");
        Files.createDirectories(path.getParent());
        String legacy="""
                {"schemaVersion":1,"points":[
                  {"id":"a","ident":"A","name":"Old A","kind":"AIRPORT","y":64},
                  {"id":"b","ident":"B","name":"Old B","kind":"WAYPOINT","y":99}],
                 "airways":[{"id":"j","name":"J1","fromId":"a","toId":"b"}],
                 "routes":[{"id":"r","name":"PRIVATE","pointIds":["a","b"]}],
                 "activeRouteId":"r","activeLeg":1}
                """;
        Files.writeString(path,legacy);
        var loaded=store.load("legacy","d","legacy");
        assertEquals(ChartData.SCHEMA_VERSION,loaded.schemaVersion);assertEquals(java.util.List.of("a","b"),loaded.airways.getFirst().pointIds);
        assertEquals(java.util.List.of("DIR"),loaded.routes.getFirst().vias);
        assertEquals("b",loaded.target().id);assertEquals("",loaded.point("b").name);assertEquals(0,loaded.point("b").y);
        assertEquals(64,loaded.point("a").y);assertEquals(legacy,Files.readString(path.resolveSibling("chart.v1.json.bak")));
        assertTrue(Files.readString(path).contains("\"schemaVersion\": "+ChartData.SCHEMA_VERSION));
        store.save("legacy","d",loaded);store.load("legacy","d","legacy");
        assertEquals(legacy,Files.readString(path.resolveSibling("chart.v1.json.bak")));
    }
    @Test void schemaTwoUpgradesWithNoInventedGapsAndKeepsPermanentBackup() throws IOException {
        var store=new ChartStore(root);Path path=store.directory("v2","d").resolve("chart.json");Files.createDirectories(path.getParent());
        String old="""
                {"schemaVersion":2,"points":[{"id":"a","ident":"A","kind":"WAYPOINT"},{"id":"b","ident":"B","kind":"WAYPOINT"}],
                 "airways":[{"id":"j","name":"J1","pointIds":["a","b"]}],
                 "routes":[{"id":"r","pointIds":["a","b"],"vias":["DIR","J1"]}],"activeRouteId":"r","activeLeg":1}
                """;
        Files.writeString(path,old);var upgraded=store.load("v2","d","v2");
        assertEquals(ChartData.SCHEMA_VERSION,upgraded.schemaVersion);assertEquals("b",upgraded.target().id);
        assertFalse(upgraded.airways.getFirst().oneWay);assertTrue(upgraded.procedures.isEmpty());
        assertEquals(java.util.List.of(false,false),upgraded.routes.getFirst().discontinuities);
        assertEquals(old,Files.readString(path.resolveSibling("chart.v2.json.bak")));
    }
    @Test void terminalMetadataGapsAndOneWayRoundTrip() throws IOException {
        var store=new ChartStore(root);var c=new ChartData();
        var dep=new ChartData.Point("DEP",ChartData.Kind.AIRPORT,0.5,64,0.5);
        var arr=new ChartData.Point("ARR",ChartData.Kind.AIRPORT,300.5,64,0.5);
        var a=new ChartData.Point("A",ChartData.Kind.WAYPOINT,100.5,0,-0.5);c.points.addAll(java.util.List.of(dep,a,arr));
        var r=new ChartData.Runway();r.airportId=dep.id;r.name="27L/09R";r.x2=100;c.runways.add(r);
        var p=new ChartData.Procedure();p.airportId=dep.id;p.runwayId=r.id;p.runwayEnd="09R";p.name="SID1";p.pointIds.add(a.id);c.procedures.add(p);
        var airway=new ChartData.Airway();airway.name="J1";airway.oneWay=true;airway.pointIds.addAll(java.util.List.of(a.id,arr.id));c.airways.add(airway);
        var route=new ChartData.Route();c.routes.add(route);dev.airac.nav.RoutePlanner.endpoints(c,route,dep,arr);
        dev.airac.nav.RoutePlanner.terminal(c,route,true,r.id,"27L",p.id);route.editAfter=1;c.activeLeg=2;
        store.save("new","d",c);var loaded=store.load("new","d","new");var plan=loaded.activeRoute();
        assertEquals(p.id,plan.departureProcedureId);assertEquals("27L",plan.departureRunwayEnd);
        assertEquals(java.util.List.of(ChartData.thresholdId(r.id,"27L"),a.id),plan.pointIds);assertNull(loaded.target());assertEquals(-1,plan.editAfter);
        assertTrue(loaded.airways.getFirst().oneWay);assertEquals(100.5,loaded.point(a.id).x);
    }
    @Test void v3SidProgramsMigrateOnceWithoutChangingPersonalGeometry() throws IOException {
        var store=new ChartStore(root);var c=new ChartData();
        var dep=new ChartData.Point("DEP",ChartData.Kind.AIRPORT,0,64,0);
        var arr=new ChartData.Point("ARR",ChartData.Kind.AIRPORT,500,64,0);
        var a=new ChartData.Point("A",ChartData.Kind.WAYPOINT,100,0,0);
        var b=new ChartData.Point("B",ChartData.Kind.WAYPOINT,200,0,0);
        c.points.addAll(java.util.List.of(dep,a,b,arr));
        var runway=new ChartData.Runway();runway.airportId=dep.id;runway.name="27L/09R";runway.x2=100;c.runways.add(runway);
        var proc=new ChartData.Procedure();proc.airportId=dep.id;proc.runwayId=runway.id;proc.runwayEnd="27L";
        proc.name="OLD1";proc.pointIds.addAll(java.util.List.of(a.id,b.id));c.procedures.add(proc);
        var route=new ChartData.Route();route.name="PERSONAL";route.pointIds.addAll(java.util.List.of(dep.id,a.id,b.id,arr.id));
        route.vias.addAll(java.util.List.of("DIR","OLD1","OLD1","DIR"));route.sections.addAll(java.util.List.of("","SID","SID",""));
        route.discontinuities.addAll(java.util.List.of(false,false,false,true));
        route.departureId=dep.id;route.arrivalId=arr.id;route.departureRunwayId=runway.id;route.departureRunwayEnd="27L";
        route.departureProcedureId=proc.id;route.departureConfigured=true;c.routes.add(route);c.activeRouteId=route.id;c.activeLeg=2;
        c.schemaVersion=3;String original=new com.google.gson.Gson().toJson(c);
        Path path=store.directory("v3","d").resolve("chart.json");Files.createDirectories(path.getParent());Files.writeString(path,original);
        var loaded=store.load("v3","d","v3");
        assertEquals(java.util.List.of(b.id,a.id),loaded.procedures.getFirst().pointIds);
        assertEquals("09R",loaded.procedures.getFirst().runwayEnd);
        assertEquals(java.util.List.of(ChartData.thresholdId(runway.id,"27L"),a.id,b.id),loaded.activeRoute().pointIds);
        assertEquals(proc.id,loaded.activeRoute().departureProcedureId);assertEquals(2,loaded.activeLeg);
        assertEquals(original,Files.readString(path.resolveSibling("chart.v3.json.bak")));
        assertEquals(loaded.procedures.getFirst().pointIds,store.load("v3","d","v3").procedures.getFirst().pointIds);
    }
    @Test void accountMigrationClaimsPrivatePlansOnlyOnceAndKeepsAccountsIndependent() throws IOException {
        var store=new ChartStore(root);var data=new ChartData();
        var point=new ChartData.Point("FIX",ChartData.Kind.WAYPOINT,0,0,0);data.points.add(point);
        var plan=new ChartData.Route();plan.name="OWNER";plan.pointIds.add(point.id);data.routes.add(plan);data.activate(plan);
        store.save("server:w","d",data);
        var first=store.loadPersonal("server:w","d","world","alice");
        var second=store.loadPersonal("server:w","d","world","bob");
        assertEquals(1,first.routes.size());assertTrue(second.routes.isEmpty());assertEquals(1,second.points.size());
        second.points.getFirst().ident="BOB";store.save(ChartStore.personalIdentity("server:w","bob"),"d",second);
        assertEquals("FIX",store.loadPersonal("server:w","d","world","alice").points.getFirst().ident);
        assertEquals("OWNER",store.loadPersonal("server:w","d","world","alice").routes.getFirst().name);
        assertTrue(store.loadPersonal("server:w","other","world","alice").routes.isEmpty());
        assertTrue(store.loadPersonal("server:w|efb:new-world","d","world","alice").routes.isEmpty());
    }
    @Test void malformedLegacyAirwayIsNeverRewritten() throws IOException {
        var store=new ChartStore(root);Path path=store.directory("bad","d").resolve("chart.json");Files.createDirectories(path.getParent());
        String invalid="{\"schemaVersion\":1,\"airways\":[{\"fromId\":\"missing\",\"toId\":\"alsoMissing\"}]}";
        Files.writeString(path,invalid);assertThrows(IOException.class,()->store.load("bad","d","bad"));assertEquals(invalid,Files.readString(path));
        assertFalse(Files.exists(path.resolveSibling("chart.v1.json.bak")));
    }
    @Test void settingsAndDisplayModesPersist() throws IOException {
        var store=new ChartStore(root);var s=new NdSettings();s.mode=NdSettings.Mode.NAV;s.corner=NdSettings.Corner.BOTTOM_LEFT;s.terrain=false;
        s.resize(140);s.range(2);
        store.saveSettings(s);var copy=store.loadSettings();assertFalse(copy.terrain);assertEquals(s.corner,copy.corner);assertEquals(s.mode,copy.mode);
        assertEquals(330,copy.size);assertEquals(4096,copy.range());
    }
    @Test void identitiesCannotEscapeStorageDirectory() {
        var store=new ChartStore(root);assertTrue(store.directory("../../other","../../d").normalize().startsWith(root));
        assertNotEquals(ChartStore.key("server:one"),ChartStore.key("server:two"));
    }

    @Test void v4AirportAnchorsUpgradeToSelectedThresholdAndBackupOnce() throws IOException {
        var store=new ChartStore(root);var c=new ChartData();
        var dep=new ChartData.Point("DEP",ChartData.Kind.AIRPORT,0,64,0);
        var arr=new ChartData.Point("ARR",ChartData.Kind.AIRPORT,500,64,0);
        var fix=new ChartData.Point("FIX",ChartData.Kind.WAYPOINT,200,0,0);
        c.points.addAll(java.util.List.of(dep,fix,arr));
        var rw=new ChartData.Runway();rw.airportId=arr.id;rw.name="27L/09R";rw.x1=450.5;rw.x2=550.5;c.runways.add(rw);
        var route=new ChartData.Route();c.routes.add(route);
        route.departureId=dep.id;route.arrivalId=arr.id;route.arrivalRunwayId=rw.id;route.arrivalRunwayEnd="09R";
        route.pointIds.addAll(java.util.List.of(dep.id,fix.id,arr.id));route.normalizeVias();route.discontinuities.set(2,true);
        c.activate(route);c.activeLeg=1;c.schemaVersion=4;
        String original=new com.google.gson.Gson().toJson(c);
        Path path=store.directory("v4","d").resolve("chart.json");Files.createDirectories(path.getParent());Files.writeString(path,original);
        var loaded=store.load("v4","d","v4");var plan=loaded.activeRoute();
        assertEquals(java.util.List.of(fix.id,ChartData.thresholdId(rw.id,"09R")),plan.pointIds);
        assertEquals(fix.id,loaded.target().id);assertTrue(plan.disconnectedBefore(1));
        assertEquals(550.5,loaded.point(plan.pointIds.getLast()).x);
        assertEquals(original,Files.readString(path.resolveSibling("chart.v4.json.bak")));
        store.save("v4","d",loaded);assertEquals(plan.pointIds,store.load("v4","d","v4").activeRoute().pointIds);
        assertEquals(original,Files.readString(path.resolveSibling("chart.v4.json.bak")));
    }

    @Test void v4MigrationKeepsTheCurrentOccurrenceOfRepeatedFix() {
        String old="""
            {"schemaVersion":4,"points":[
              {"id":"d","ident":"DEP","kind":"AIRPORT"},
              {"id":"a","ident":"A","kind":"WAYPOINT"},
              {"id":"b","ident":"B","kind":"WAYPOINT"},
              {"id":"r","ident":"ARR","kind":"AIRPORT"}],
             "routes":[{"id":"plan","departureId":"d","arrivalId":"r","pointIds":["d","a","b","a","r"]}],
             "activeRouteId":"plan","activeLeg":3}
            """;
        var loaded=ChartMigration.read(com.google.gson.JsonParser.parseString(old).getAsJsonObject());
        assertEquals(java.util.List.of("a","b","a"),loaded.activeRoute().pointIds);
        assertEquals(2,loaded.activeLeg);assertEquals("a",loaded.target().id);
    }
}
