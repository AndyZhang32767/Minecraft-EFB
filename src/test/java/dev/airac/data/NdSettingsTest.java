package dev.airac.data;

import dev.airac.data.ChartData.Kind;
import dev.airac.data.NdSettings.Mode;
import dev.airac.data.NdSettings.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class NdSettingsTest {
    @TempDir Path root;
    @Test void compactSizesAreSelectableAndPersist() throws IOException {
        var settings=new NdSettings();
        settings.resizePreset(-1);assertEquals(180,settings.size);
        settings.resizePreset(-1);settings.resizePreset(-1);assertEquals(140,settings.size);
        var store=new ChartStore(root);
        for(int size:new int[]{120,100}) {
            settings.resizePreset(-1);assertEquals(size,settings.size);
            store.saveSettings(settings);assertEquals(size,store.loadSettings().size);
        }
        settings.resizePreset(-1);assertEquals(100,settings.size);
        settings.resizePreset(1);assertEquals(120,settings.size);
    }
    @Test void automaticRangeUsesAltitudeBands() {
        var s=new NdSettings();s.autoRange=true;
        double[] altitudes={-64,149.9,150,199.9,200,249.9,250,299.9,300,349.9,350,399.9,400,1000};
        int[] ranges={128,128,128,128,256,256,512,512,1024,1024,2048,2048,4096,4096};
        for(int i=0;i<altitudes.length;i++)assertEquals(ranges[i],s.rangeAtAltitude(altitudes[i]),"Y="+altitudes[i]);
        assertEquals(128,s.rangeAtAltitude(Double.NaN));
    }
    @Test void togglingAutoKeepsTheManualSelection() {
        var s=new NdSettings();s.rangeIndex=6;
        assertFalse(s.autoRange);assertEquals(8192,s.rangeAtAltitude(275));
        s.autoRange=true;assertEquals(512,s.rangeAtAltitude(275));assertEquals(8192,s.range());
        s.autoRange=false;assertEquals(8192,s.rangeAtAltitude(200));
    }
    @Test void autoRangePersistsAndLegacySettingsKeepManualRange() throws IOException {
        var store=new ChartStore(root);
        Files.writeString(root.resolve("settings.json"),"{\"rangeIndex\":4,\"size\":190}");
        var s=store.loadSettings();assertFalse(s.autoRange);assertEquals(2048,s.rangeAtAltitude(0));
        s.autoRange=true;store.saveSettings(s);
        var loaded=store.loadSettings();assertTrue(loaded.autoRange);assertEquals(128,loaded.rangeAtAltitude(0));
        loaded.autoRange=false;assertEquals(2048,loaded.rangeAtAltitude(200));
        assertFalse(loaded.ndRadio1);assertFalse(loaded.ndRadio2);
        loaded.ndRadio1=true;store.saveSettings(loaded);
        assertTrue(store.loadSettings().ndRadio1);assertFalse(store.loadSettings().ndRadio2);
    }
    @Test void modesToggleBetweenNavAndArcAndReferenceStaysExclusiveOfConstraints() {
        var s=new NdSettings();s.mode=Mode.ARC;
        s.toggleMode();assertEquals(Mode.NAV,s.mode);
        s.toggleMode();assertEquals(Mode.ARC,s.mode);
        assertFalse(s.shows(Kind.AIRPORT,false));
        s.selectReference(Reference.WPT);s.selectReference(Reference.APRT);
        assertEquals(Reference.APRT,s.reference);assertTrue(s.shows(Kind.AIRPORT,false));assertFalse(s.shows(Kind.VOR,false));
        assertTrue(s.shows(Kind.WAYPOINT,true));
        s.cstr=true;s.selectReference(Reference.APRT);
        assertEquals(Reference.NONE,s.reference);assertTrue(s.cstr);
    }
}
