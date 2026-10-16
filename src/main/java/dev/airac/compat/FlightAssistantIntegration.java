package dev.airac.compat;

import dev.airac.AiracClient;
import dev.airac.data.ChartData;
import net.fabricmc.loader.api.FabricLoader;

/** Keeps FA classes out of client/server class loading when the optional mod is absent. */
public final class FlightAssistantIntegration {
    public interface Adapter {
        void apply(ChartData chart, ChartData.Route route);
        void tick();
        void stop();
        boolean active();
        Object flightPlanMode(Object plan,int axis);
    }
    private static Adapter adapter;
    private FlightAssistantIntegration() {}
    public static void init() {
        if(!FabricLoader.getInstance().isModLoaded("flightassistant"))return;
        try { adapter=FaAutopilot.register(); }
        catch(LinkageError | RuntimeException ex) { AiracClient.LOGGER.error("FA integration could not initialize",ex); }
    }
    public static boolean available(){return adapter!=null;}
    public static boolean active(){return adapter!=null && adapter.active();}
    /** Called only by the optional FA plan mixin; no FA types leak into EFB's entrypoint. */
    public static Object flightPlanMode(Object plan,int axis){return adapter==null?null:adapter.flightPlanMode(plan,axis);}
    public static void apply(ChartData chart,ChartData.Route route) {
        if(adapter==null)throw new IllegalArgumentException("fa_unavailable");
        try {adapter.apply(chart,route);}
        catch(LinkageError ex){AiracClient.LOGGER.error("Incompatible FA API",ex);stop();throw new IllegalArgumentException("fa_incompatible");}
    }
    public static void tick(){
        if(adapter==null)return;
        try {adapter.tick();}
        catch(RuntimeException | LinkageError ex) {
            AiracClient.LOGGER.error("FA adapter stopped after a control error",ex);
            adapter.stop();AiracClient.INSTANCE.error=dev.airac.ui.Ui.tr("ui.airac.fa_incompatible");
        }
    }
    public static void stop(){if(adapter!=null)adapter.stop();}
}
