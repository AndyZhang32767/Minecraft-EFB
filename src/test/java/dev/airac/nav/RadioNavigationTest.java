package dev.airac.nav;

import dev.airac.data.*;
import dev.airac.data.ChartData.*;
import dev.airac.sync.SharedCharts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RadioNavigationTest {
    @TempDir Path root;
    Point station(ChartData chart,Kind kind,double x,double y,double z,int frequency) {
        if(kind==Kind.ILS) {
            Point airport=new Point("APT",Kind.AIRPORT,x,y,z);chart.points.add(airport);
            Runway r=new Runway();r.airportId=airport.id;r.x1=x;r.z1=z;r.x2=x;r.z2=z-100;
            r.name="36/18";r.ilsEnabled=true;r.ilsFrequency1=frequency;r.ilsFrequency2=110350;
            r.ilsSlope=1/Math.tan(Math.toRadians(3));chart.runways.add(r);
            return RunwayIls.station(chart,r,"36");
        }
        Point p=RadioNavigation.createStation(chart,kind,x,y,z);p.x=x;p.y=y;p.z=z;p.frequency=frequency;
        chart.points.add(p);return p;
    }
    Radio tune(ChartData chart,int index,Kind mode,int frequency) {
        Radio r=chart.radios.get(index);r.mode=mode;r.frequency=frequency;return r;
    }
    @Test void civilChannelsParseExactlyAndDoNotCrossModes() {
        assertEquals(350,RadioNavigation.parseFrequency(Kind.NDB,"350"));
        assertEquals(110300,RadioNavigation.parseFrequency(Kind.ILS,"110.30"));
        assertEquals(113950,RadioNavigation.parseFrequency(Kind.VOR,"113.95"));
        for(String value:List.of("NaN","Infinity","110.301","abc","111.00","118.00","110300"))
            assertThrows(IllegalArgumentException.class,()->RadioNavigation.parseFrequency(Kind.ILS,value));
        assertThrows(IllegalArgumentException.class,()->RadioNavigation.parseFrequency(Kind.VOR,"110.30"));
        assertThrows(IllegalArgumentException.class,()->RadioNavigation.parseFrequency(Kind.NDB,"350.5"));
        assertEquals("110.30",RadioNavigation.frequency(Kind.ILS,110300));
    }
    @Test void randomCreationUsesUnusedValidChannelsAndCentersNegativeBlocks() {
        for(Kind kind:List.of(Kind.NDB,Kind.VOR)) {
            ChartData c=new ChartData();Set<Integer> frequencies=new HashSet<>();Set<String> ids=new HashSet<>();
            for(int i=0;i<20;i++){
                Point p=RadioNavigation.createStation(c,kind,-10.9,64.25,12.8);c.points.add(p);
                assertTrue(RadioNavigation.validFrequency(kind,p.frequency));assertTrue(frequencies.add(p.frequency));
                assertTrue(ids.add(p.ident));assertEquals(-10.5,p.x);assertEquals(12.5,p.z);assertEquals(64.25,p.y);
            }
            c.validate();
        }
    }
    @Test void receptionRequiresModeFrequencyAndInclusiveHorizontalRadius() {
        ChartData c=new ChartData();Point p=station(c,Kind.NDB,0,64,0,350);p.radius=100;
        Radio r=tune(c,0,Kind.NDB,350);
        assertNotNull(RadioNavigation.receive(c,r,60,1000,80,0));
        assertNull(RadioNavigation.receive(c,r,60.01,64,80,0));
        r.frequency=351;assertNull(RadioNavigation.receive(c,r,0,64,0,0));
        r.mode=Kind.VOR;r.frequency=113000;assertNull(RadioNavigation.receive(c,r,0,64,0,0));
        r.frequency=0;assertNull(RadioNavigation.receive(c,r,0,64,0,0));
    }
    @Test void duplicateFrequencyChoosesNearestInRangeAndFallsBackAfterDeletion() {
        ChartData c=new ChartData();Point far=station(c,Kind.NDB,100,0,0,350),near=station(c,Kind.NDB,10,0,0,350);
        Radio r=tune(c,0,Kind.NDB,350);
        assertSame(near,RadioNavigation.receive(c,r,0,0,0,0).station());
        c.removePoint(near.id);assertSame(far,RadioNavigation.receive(c,r,0,0,0,0).station());
        far.radius=50;assertNull(RadioNavigation.receive(c,r,0,0,0,0));
    }
    @Test void automaticVorUsesTheNearestStationInsideItsRadius() {
        ChartData c=new ChartData();
        Point far=station(c,Kind.VOR,300,0,0,113000),near=station(c,Kind.VOR,40,0,0,113500),ndb=station(c,Kind.NDB,5,0,0,350);
        far.radius=1000;near.radius=100;ndb.radius=1000;
        assertSame(near,RadioNavigation.nearestVor(c,0,0));
        near.radius=30;assertSame(far,RadioNavigation.nearestVor(c,0,0));
        far.radius=10;assertNull(RadioNavigation.nearestVor(c,0,0));
        Radio radio=c.radios.getFirst();
        near.radius=100;assertTrue(RadioNavigation.tuneNearestVor(radio,c,0,0));
        assertEquals(Kind.VOR,radio.mode);assertEquals(near.frequency,radio.frequency);
        assertFalse(RadioNavigation.tuneNearestVor(radio,c,0,0));
        radio.mode=Kind.NDB;radio.frequency=350;
        Radio second=c.radios.get(1);
        assertTrue(RadioNavigation.tuneNearestVor(second,c,0,0));
        assertEquals(Kind.VOR,second.mode);assertEquals(near.frequency,second.frequency);
        assertEquals(Kind.NDB,radio.mode);assertEquals(350,radio.frequency);
    }
    @Test void ndbTracksTrueAndRelativeBearingAsHeadingChanges() {
        ChartData c=new ChartData();station(c,Kind.NDB,100,0,0,350);Radio r=tune(c,0,Kind.NDB,350);
        var signal=RadioNavigation.receive(c,r,0,0,0,30);
        assertEquals(90,signal.bearing(),1e-9);assertEquals(60,signal.relativeBearing(),1e-9);
        assertEquals(350,RadioNavigation.receive(c,r,0,0,0,100).relativeBearing(),1e-9);
        assertTrue(signal.hasBearing());assertFalse(signal.hasDme());
    }
    @Test void vorRadialUsesNorthOffsetAndModesGateBearingAndDme() {
        ChartData c=new ChartData();Point p=station(c,Kind.VOR,0,0,0,113000);p.declination=20;
        Radio r=tune(c,0,Kind.VOR,113000);
        var signal=RadioNavigation.receive(c,r,100,75,0,0);
        assertEquals(70,signal.radial(),1e-9);assertEquals(125,signal.distance(),1e-9);
        assertTrue(signal.hasBearing());assertTrue(signal.hasDme());
        p.vorMode=VorMode.DME;signal=RadioNavigation.receive(c,r,100,75,0,0);
        assertFalse(signal.hasBearing());assertTrue(signal.hasDme());
        p.vorMode=VorMode.VOR;signal=RadioNavigation.receive(c,r,100,75,0,0);
        assertTrue(signal.hasBearing());assertFalse(signal.hasDme());
    }
    @Test void ilsCourseAndGlideSlopeUseStationElevationAndTrueHeading() {
        ChartData c=new ChartData();Point p=station(c,Kind.ILS,0,64,0,110300);p.course=0;p.glideSlope=3;
        Radio r=tune(c,0,Kind.ILS,110300);
        double y=64+1000*Math.tan(Math.toRadians(3));
        var signal=RadioNavigation.receive(c,r,0,y,1000,180);
        assertEquals(0,signal.localizer(),1e-9);assertEquals(0,signal.glideSlope(),1e-9);
        assertTrue(RadioNavigation.receive(c,r,0,y+10,1000,0).glideSlope()>0);
        assertTrue(Math.abs(RadioNavigation.receive(c,r,100,y,1000,0).localizer())>0);
        c.runways.getFirst().x2=100;c.runways.getFirst().z2=0;
        assertEquals(0,RadioNavigation.receive(c,r,-1000,y,0,0).localizer(),1e-9);
    }
    @Test void receiversAreIndependentAndSharedSnapshotsNeverCarryOrOverwriteTuning() {
        ChartData c=new ChartData();station(c,Kind.NDB,0,0,0,350);station(c,Kind.VOR,10,0,0,113000);
        Radio a=tune(c,0,Kind.NDB,350),b=tune(c,1,Kind.VOR,113000);
        assertEquals(Kind.NDB,RadioNavigation.receive(c,a,0,0,0,0).station().kind);
        assertEquals(Kind.VOR,RadioNavigation.receive(c,b,0,0,0,0).station().kind);
        String packet=new String(SharedCharts.encode(c),java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(packet.contains("radios"));
        ChartData other=SharedCharts.decode(SharedCharts.encode(c));
        assertEquals(0,other.radios.getFirst().frequency);
        other.removePoint(other.points.getFirst().id);SharedCharts.apply(c,other);
        assertSame(a,c.radios.getFirst());assertEquals(350,a.frequency);assertEquals(113000,b.frequency);
        assertNull(RadioNavigation.receive(c,a,0,0,0,0));assertNotNull(RadioNavigation.receive(c,b,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->SharedCharts.decode("{\"radios\":[]}".getBytes()));
    }
    @Test void navaidsAndPrivateTuningPersistWithoutLeakingToOtherAccountsOrDimensions() throws Exception {
        ChartStore store=new ChartStore(root);ChartData c=new ChartData();
        station(c,Kind.ILS,12.5,70,-30.5,110300);
        c.runways.getFirst().ilsSlope=25;
        tune(c,1,Kind.ILS,110300);store.save("world","d",c);
        ChartData alice=store.loadPersonal("world","d","world","alice");
        ChartData bob=store.loadPersonal("world","d","world","bob");
        assertEquals(110300,alice.radios.get(1).frequency);assertEquals(0,bob.radios.get(1).frequency);
        Point ils=RunwayIls.stations(bob).getFirst();
        assertEquals(0,ils.course);assertEquals(70,ils.y);
        assertEquals(Math.toDegrees(Math.atan(1.0/25)),ils.glideSlope);assertEquals(2,RunwayIls.stations(bob).size());
        assertEquals(0,store.loadPersonal("world","other","world","alice").radios.get(1).frequency);
    }
    @Test void schemaFiveUpgradesOnceWithNoInventedStationsOrTuning() throws Exception {
        ChartStore store=new ChartStore(root);
        Path path=store.directory("old","d").resolve("chart.json");Files.createDirectories(path.getParent());
        String old="{\"schemaVersion\":5,\"points\":[{\"id\":\"fix\",\"ident\":\"FIX\",\"kind\":\"WAYPOINT\"}]}";
        Files.writeString(path,old);ChartData c=store.load("old","d","old");
        assertEquals(ChartData.SCHEMA_VERSION,c.schemaVersion);assertEquals(1,c.points.size());assertEquals(Kind.WAYPOINT,c.points.getFirst().kind);
        assertEquals(2,c.radios.size());assertTrue(c.radios.stream().allMatch(r->r.frequency==0));
        assertEquals(old,Files.readString(path.resolveSibling("chart.v5.json.bak")));
        store.save("old","d",c);assertEquals(old,Files.readString(path.resolveSibling("chart.v5.json.bak")));
    }
    @Test void untunedReceiverModesAlsoStayAccountPrivate() throws Exception {
        ChartStore store=new ChartStore(root);ChartData c=new ChartData();
        c.radios.get(1).mode=Kind.ILS;store.save("legacy","d",c);
        assertEquals(Kind.ILS,store.loadPersonal("legacy","d","world","alice").radios.get(1).mode);
        ChartData bob=store.loadPersonal("legacy","d","world","bob");
        assertTrue(bob.radios.stream().allMatch(r->r.mode==Kind.NDB && r.frequency==0));
    }
    @Test void malformedStationDataAndCorruptPersonalTuningAreRejected() {
        ChartData c=new ChartData();Point p=station(c,Kind.VOR,0,0,0,113000);
        for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,1_000_001}){
            p.radius=value;assertThrows(IllegalArgumentException.class,c::validate);
        }
        p.radius=100;p.declination=181;assertThrows(IllegalArgumentException.class,c::validate);
        p.declination=0;c.radios.getFirst().mode=Kind.AIRPORT;assertThrows(IllegalArgumentException.class,c::validate);
    }
}
