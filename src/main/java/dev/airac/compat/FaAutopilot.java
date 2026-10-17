package dev.airac.compat;

import dev.airac.AiracClient;
import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import dev.airac.nav.*;
import dev.airac.ui.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import ru.octol1ttle.flightassistant.api.autoflight.ControlInput;
import ru.octol1ttle.flightassistant.api.autoflight.thrust.*;
import ru.octol1ttle.flightassistant.api.computer.*;
import ru.octol1ttle.flightassistant.api.util.PIDController;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.AutoFlightComputer;
import ru.octol1ttle.flightassistant.screen.autoflight.AutoFlightScreen;
import ru.octol1ttle.flightassistant.screen.autoflight.AutoFlightScreenState;


/** Supplies EFB guidance through FA's Flight Plan mode, retaining FA protections and disconnect. */
final class FaAutopilot implements FlightAssistantIntegration.Adapter, ThrustSource {
    private ComputerBus bus;
    private ChartData source;
    private Route route;
    private PerformanceProfile profile;
    private int cursor;
    private double previousX,previousZ;
    private boolean swept,owned,ilsCaptured;
    private int lastFireworkTick=Integer.MIN_VALUE;
    private final MinecraftClient client=MinecraftClient.getInstance();
    private final Lateral lateral=new Lateral();
    private final Vertical vertical=new Vertical();
    private final Thrust thrust=new Thrust();
    private double along;
    private RadioNavigation.Signal signal;
    private PIDController verticalController;
    private double commandedPitch;
    private int importedCursor=-1;
    private String capturedStation;

    static FaAutopilot register() {
        FaAutopilot bridge=new FaAutopilot();
        ComputerRegistrationCallback.EVENT.register((computers,register)->bridge.bus=computers);
        ThrustSourceRegistrationCallback.EVENT.register(register->register.accept(bridge));
        return bridge;
    }
    @Override public void apply(ChartData chart,Route selected) {
        if(bus==null || client.player==null || !faEnabled() || bus.getAutoflight().isDisabledOrFaulted()
                || bus.getPitch().isDisabledOrFaulted() || bus.getHeading().isDisabledOrFaulted()
                || bus.getThrust().isDisabledOrFaulted() || bus.getPlan().isDisabledOrFaulted()
                || bus.getProtections().getProtectionsLost() || !bus.getChunk().isCurrentLoaded())
            throw new IllegalArgumentException("fa_not_ready");
        PerformanceProfile next=new PerformanceProfile(chart,selected,client.player.getY());
        if(!next.feasible)throw new IllegalArgumentException("perf_short_route");
        if(!selected.performance.configured)throw new IllegalArgumentException("perf_not_set");
        // Construct/import before taking control. Never engage after a partial conversion.
        stop();
        try { FaPlanImport.load(bus.getPlan(),next); }
        catch(ReflectiveOperationException | LinkageError ex) {
            AiracClient.LOGGER.error("FA flight plan import failed",ex);
            throw new IllegalArgumentException("fa_incompatible");
        }
        importedCursor=-1;capturedStation=null;
        source=chart;route=selected;profile=next;cursor=0;along=0;swept=false;ilsCaptured=false;
        verticalController=new PIDController(1.8,.1,.35,10,-85,85);
        commandedPitch=bus.getData().getPitch();
        if(next.ilsFrequency!=0) {
            // Approach ILS always uses RADIO 1. RADIO 2 AUTO keeps tuning the nearest VOR.
            Radio radio=chart.radios.getFirst();radio.mode=Kind.ILS;radio.frequency=next.ilsFrequency;
        }
        var ap=bus.getAutoflight();
        var state=AutoFlightScreen.Companion.getState();
        state.setLateralMode(AutoFlightScreenState.LateralMode.FLIGHT_PLAN);
        state.setVerticalMode(AutoFlightScreenState.VerticalMode.FLIGHT_PLAN);
        state.setThrustMode(AutoFlightScreenState.ThrustMode.FLIGHT_PLAN);
        state.apply(ap);
        ap.setFlightDirectors(true);ap.setAutoThrust(true,false);ap.setAutoPilot(true,false);owned=true;
        chart.activeLeg=0;
        tick();
    }
    private boolean faEnabled() {
        try {
            Class<?> config=Class.forName("ru.octol1ttle.flightassistant.config.FAConfig");
            Object instance=config.getField("INSTANCE").get(null);
            Object global=java.util.Arrays.stream(config.getMethods()).filter(m->m.getName().startsWith("getGlobal") && m.getParameterCount()==0).findFirst().orElseThrow().invoke(instance);
            return (boolean)java.util.Arrays.stream(global.getClass().getMethods()).filter(m->m.getName().startsWith("getModEnabled") && m.getParameterCount()==0).findFirst().orElseThrow().invoke(global);
        } catch(ReflectiveOperationException | java.util.NoSuchElementException ex){return false;}
    }
    @Override public boolean active(){return owned;}
    @Override public Object flightPlanMode(Object plan,int axis) {
        if(!owned || bus==null || bus.getPlan()!=plan)return null;
        // A dropped AP must not come back under EFB guidance. The next engage uses FA's own plan modes.
        if(!bus.getAutoflight().getAutopilot()){release();return null;}
        return switch(axis){case 0->lateral;case 1->vertical;case 2->thrust;default->null;};
    }
    /** Drop EFB guidance without touching AP. Re-engaging stays on FA until TMPY applies again. */
    private void release() {
        owned=false;
        profile=null;source=null;route=null;signal=null;ilsCaptured=false;
    }
    @Override public void stop() {
        if(!owned || bus==null)return;
        var ap=bus.getAutoflight();
        boolean stillOurs=ap.getSelectedLateralMode()==null && ap.getSelectedVerticalMode()==null && ap.getSelectedThrustMode()==null;
        release();
        if(stillOurs){ap.setAutoPilot(false,false);ap.setAutoThrust(false,false);ap.setFlightDirectors(false);}
        bus.getThrust().setTarget(0,null);
    }
    @Override public void tick() {
        if(!owned)return;
        var app=AiracClient.INSTANCE;
        var ap=bus.getAutoflight();
        if(!ap.getAutopilot()){release();return;}
        if(client.player==null || client.world==null || app.chart!=source || source.activeRoute()!=route
                || !faEnabled() || bus.getProtections().getProtectionsLost() || bus.getPitch().isDisabledOrFaulted()
                || bus.getHeading().isDisabledOrFaulted() || bus.getThrust().isDisabledOrFaulted()
                || ap.getSelectedLateralMode()!=null || ap.getSelectedVerticalMode()!=null
                || ap.getSelectedThrustMode()!=null || bus.getAutoflight().isDisabledOrFaulted()) {
            stop();return;
        }
        if(client.isPaused())return;
        double x=client.player.getX(),z=client.player.getZ();
        double speed=client.player.getVelocity().horizontalLength()*20;
        var target=profile.nodes.get(cursor);
        double radius=Math.clamp(speed*.25,5,30);
        boolean passed=swept && NavMath.distance(previousX,previousZ,x,z)<Math.max(64,speed)
                && NavMath.segmentDistance(target.x(),target.z(),previousX,previousZ,x,z)<radius;
        if(NavMath.distance(x,z,target.x(),target.z())<radius || passed) {
            forgetFlown(target);
            if(cursor==profile.nodes.size()-1) {
                source.activeLeg=route.pointIds.size();stop();app.save();return;
            }
            cursor++;target=profile.nodes.get(cursor);
        }
        previousX=x;previousZ=z;swept=true;
        int nextIndex=liveLeg(cursor);
        if(source.activeLeg!=nextIndex){source.activeLeg=nextIndex;app.save();}
        along=target.distance();
        if(cursor>0) {
            var origin=profile.nodes.get(cursor-1);
            double dx=target.x()-origin.x(),dz=target.z()-origin.z(),length=dx*dx+dz*dz;
            double fraction=length==0?1:Math.clamp(((x-origin.x())*dx+(z-origin.z())*dz)/length,0,1);
            along=origin.distance()+(target.distance()-origin.distance())*fraction;
        } else along=0;
        if(importedCursor!=cursor) {
            try {FaPlanImport.track(bus.getPlan(),cursor);importedCursor=cursor;}
            catch(ReflectiveOperationException ex){stop();app.error=Ui.tr("ui.airac.fa_incompatible");return;}
        }
        Radio radio=source.radios.getFirst();
        signal=profile.ilsFrequency==0 || radio.mode!=Kind.ILS || radio.frequency!=profile.ilsFrequency || along<profile.topOfDescent ? null
                :RadioNavigation.receive(source,radio,x,client.player.getY(),z,NavMath.heading(client.player.getYaw()));
        if(signal!=null && !signal.station().id.equals(profile.arrivalIls.id))signal=null;
        if(signal!=null && !ApproachGuidance.captureAllowed(signal,x,z))signal=null;
        if(ilsCaptured && (signal==null || !signal.station().id.equals(capturedStation))) {
            stop();app.error=Ui.tr("ui.airac.fa_ils_lost");return;
        }
        if(signal!=null){ilsCaptured=true;capturedStation=signal.station().id;}
        updatePitch();
    }
    /** A fix that has been flown is removed from the personal route, so a later TMPY cannot import it. */
    private void forgetFlown(PerformanceProfile.Node node) {
        if(node.pointId()==null)return;
        int index=route.pointIds.indexOf(node.pointId());
        if(index<0)return;
        route.removeAt(index);
        if(index>0 && index<route.discontinuities.size())route.discontinuities.set(index,false);
    }
    private int liveLeg(int nodeIndex) {
        for(int i=Math.max(0,nodeIndex);i<profile.nodes.size();i++) {
            String id=profile.nodes.get(i).pointId();
            if(id==null)continue;
            int index=route.pointIds.indexOf(id);
            if(index>=0)return index;
        }
        return route.pointIds.size();
    }
    private void updatePitch() {
        if(!bus.getData().getFlying())return;
        double altitude=bus.getData().getAltitude();
        if(signal==null && along<profile.topOfClimb && altitude<profile.cruiseAltitude-1) {
            commandedPitch=profile.climbPitch;return;
        }
        double currentVs=bus.getData().getVelocityPerSecond().y;
        double desiredVs;
        if(signal!=null)desiredVs=ApproachGuidance.verticalSpeed(signal,client.player.getX(),altitude,client.player.getZ(),
                bus.getData().getVelocityPerSecond().horizontalLength(),profile.descentRate);
        else if(along>=profile.topOfDescent && altitude>profile.descentAltitude+.5)
            desiredVs=-Math.min(profile.descentRate,Math.max(0,(altitude-profile.descentAltitude)/2));
        else desiredVs=Math.clamp(((along>=profile.topOfDescent?profile.descentAltitude:profile.cruiseAltitude)-altitude)/2,-profile.descentRate,profile.descentRate);
        commandedPitch=verticalController.calculate(desiredVs,currentVs,bus.getData().getPitch());
    }
    private ControlInput input(double target,String mode) {
        return new ControlInput((float)target,Text.literal(mode),ControlInput.Priority.NORMAL,1.5f,ControlInput.Status.ACTIVE);
    }
    private final class Lateral implements AutoFlightComputer.LateralMode {
        public Text getTextOverride(){return null;}
        public ControlInput getControlInput(ComputerBus computers) {
            if(!owned)return null;
            if(signal!=null)return input(ApproachGuidance.heading(signal,client.player.getX(),client.player.getZ(),
                    computers.getData().getVelocityPerSecond().horizontalLength()),"LOC");
            var target=profile.nodes.get(cursor);
            double x=target.x(),z=target.z();
            if(cursor>0) {
                var origin=profile.nodes.get(cursor-1);
                double length=NavMath.distance(origin.x(),origin.z(),x,z);
                double distance=Math.min(target.distance(),along+Math.max(10,computers.getData().getVelocityPerSecond().horizontalLength()*2));
                double fraction=length==0?1:Math.clamp((distance-origin.distance())/length,0,1);
                x=origin.x()+(x-origin.x())*fraction;z=origin.z()+(z-origin.z())*fraction;
            }
            return input(NavMath.bearing(x-computers.getData().getX(),z-computers.getData().getZ()),"NAV");
        }
    }
    private final class Vertical implements AutoFlightComputer.VerticalMode {
        public Text getTextOverride(){return null;}
        public ControlInput getControlInput(ComputerBus computers) {
            if(!owned)return null;
            return input(commandedPitch,signal!=null?"G/S":along<profile.topOfClimb?"CLB":along>=profile.topOfDescent?"DES":"ALT CRZ");
        }
    }
    private final class Thrust implements AutoFlightComputer.ThrustMode {
        public Text getTextOverride(){return null;}
        public ControlInput getControlInput(ComputerBus computers) {
            if(!owned)return null;
            double current=computers.getData().getForwardVelocityPerSecond().length();
            double output=HighSpeedThrust.command(targetSpeed(),current,computers.getThrust().getCurrent());
            return input(output,"SPEED");
        }
    }
    @Override public Priority getPriority(){return Priority.NORMAL;}
    @Override public boolean getSupportsReverse(){return false;}
    private double targetSpeed(){return signal!=null?profile.approachSpeed:profile.speed;}
    @Override public boolean isAvailable(){return owned && profile!=null && targetSpeed()>33.33 && safeHand()!=null;}
    private static boolean safe(ItemStack stack) {
        if(!stack.isOf(Items.FIREWORK_ROCKET))return false;
        var fireworks=stack.get(DataComponentTypes.FIREWORKS);
        return fireworks==null || fireworks.explosions().isEmpty();
    }
    private Hand safeHand() {
        if(client.player==null)return null;
        if(safe(client.player.getOffHandStack()))return Hand.OFF_HAND;
        if(safe(client.player.getMainHandStack()))return Hand.MAIN_HAND;
        for(int i=0;i<9;i++)if(safe(client.player.getInventory().getStack(i)))return Hand.MAIN_HAND;
        return null;
    }
    @Override public void tickThrust(float requested) {
        if(!isAvailable() || client.player==null || client.interactionManager==null
                || !bus.getData().automationsAllowed(true) || !bus.getAutoflight().getAutopilot())return;
        int tick=client.player.age;
        double current=bus.getData().getForwardVelocityPerSecond().length();
        if(!HighSpeedThrust.shouldFire(targetSpeed(),current,requested,tick,lastFireworkTick))return;
        // Respect FA's obstacle protection before launching another rocket at boosted speed.
        if(bus.getGpws().computeObstacleImpactTime(bus.getData().getVelocityPerSecond(),bus.getGpws().getCautionThreshold())<=bus.getGpws().getWarningThreshold())return;
        Hand hand=safeHand();if(hand==null)return;
        if(hand==Hand.MAIN_HAND && !safe(client.player.getMainHandStack())) {
            for(int i=0;i<9;i++)if(safe(client.player.getInventory().getStack(i))){client.player.getInventory().setSelectedSlot(i);break;}
        }
        if(hand==Hand.MAIN_HAND && !safe(client.player.getMainHandStack()))return;
        lastFireworkTick=tick;
        client.interactionManager.interactItem(client.player,hand);
    }
}
