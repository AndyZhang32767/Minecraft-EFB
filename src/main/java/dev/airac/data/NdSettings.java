package dev.airac.data;

public final class NdSettings {
    public enum Mode { NAV, ARC }
    /** One optional NAV/ARC database layer. CSTR is separate and may be shown with it. */
    public enum Reference { NONE, WPT, VORD, NDB, APRT }
    public enum Corner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }
    public enum Visibility { FLYING, ALWAYS, HIDDEN }
    public static final int[] RANGES = {128, 256, 512, 1024, 2048, 4096, 8192};
    public Mode mode = Mode.ARC;
    public Corner corner = Corner.TOP_RIGHT;
    public Visibility visibility = Visibility.FLYING;
    public int rangeIndex = 3;
    public boolean autoRange;
    public int size = 190;
    public double captureRadius = 32;
    public boolean terrain = true;
    /** Show the tuned station on the left (RADIO 1) or right (RADIO 2) of the ND. */
    public boolean ndRadio1, ndRadio2;
    public Reference reference = Reference.NONE;
    public boolean cstr;
    public void validate() {
        if (mode == null) mode = Mode.ARC;
        if (reference == null) reference = Reference.NONE;
        if (corner == null) corner = Corner.TOP_RIGHT;
        if (visibility == null) visibility = Visibility.FLYING;
        rangeIndex = Math.clamp(rangeIndex, 0, RANGES.length - 1);
        size = Math.clamp(size, 100, 360);
        if (!Double.isFinite(captureRadius)) captureRadius = 32;
        captureRadius = Math.clamp(captureRadius, 4, 128);
    }
    public int range() { return RANGES[rangeIndex]; }
    /** World altitude Y; each 50-block band selects the next ND range. */
    public int rangeAtAltitude(double altitude) {
        if (!autoRange) return range();
        if (Double.isNaN(altitude)) altitude = 0;
        int band = (int)Math.clamp(Math.floor((altitude - 150) / 50), 0, 5);
        return RANGES[band];
    }
    public boolean mapMode() { return mode==Mode.NAV || mode==Mode.ARC; }
    public void toggleMode() { mode = mode == Mode.ARC ? Mode.NAV : Mode.ARC; }
    /** The four database layers are exclusive. Selecting the active one clears it. */
    public void selectReference(Reference next) { reference = reference==next ? Reference.NONE : next; }
    public boolean shows(ChartData.Kind kind, boolean inPlan) {
        if (inPlan) return true;
        return switch (reference) {
            case WPT -> kind==ChartData.Kind.WAYPOINT;
            case VORD -> kind==ChartData.Kind.VOR;
            case NDB -> kind==ChartData.Kind.NDB;
            case APRT -> kind==ChartData.Kind.AIRPORT;
            case NONE -> false;
        };
    }
    public void cycleCorner() { corner = Corner.values()[(corner.ordinal() + 1) % Corner.values().length]; }
    public void cycleVisibility() { visibility = Visibility.values()[(visibility.ordinal() + 1) % Visibility.values().length]; }
    public void range(int step) { rangeIndex = Math.clamp(rangeIndex + step, 0, RANGES.length - 1); }
    /** A manual key press takes over from the current automatic range. */
    public void manualRange(int step,double altitude) {
        if(autoRange) {
            int current=rangeAtAltitude(altitude);
            for(int i=0;i<RANGES.length;i++)if(RANGES[i]==current){rangeIndex=i;break;}
            autoRange=false;
        }
        range(step);
    }
    public void resize(int step) { size=Math.clamp(size+step,100,360); }
    public void resizePreset(int direction) {
        if(direction==0)return;
        // Step to an adjacent 20 px size, including 140, 120 and 100 from legacy 190 px settings.
        size = Math.clamp((direction>0?size/20+1:(size+19)/20-1)*20,100,360);
    }
}
