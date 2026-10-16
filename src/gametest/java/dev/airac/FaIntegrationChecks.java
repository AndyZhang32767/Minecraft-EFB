package dev.airac;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Loaded only when the optional FA mod is present. */
final class FaIntegrationChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void run(ClientGameTestContext ctx) {
            ctx.waitFor(client->{
                var bus=ru.octol1ttle.flightassistant.impl.computer.ComputerHost.INSTANCE;
                return bus.getChunk().isCurrentLoaded() && !bus.getProtections().getProtectionsLost();
            },200);
            ctx.clickScreenButton("TMPY");
            ctx.runOnClient(client->{
                var bus=ru.octol1ttle.flightassistant.impl.computer.ComputerHost.INSTANCE;
                check(dev.airac.compat.FlightAssistantIntegration.active(),"TMPY takes control through FA");
                check(bus.getAutoflight().getAutopilot() && bus.getAutoflight().getAutoThrust(),"AP and A/THR engaged");
                var state=ru.octol1ttle.flightassistant.screen.autoflight.AutoFlightScreen.Companion.getState();
                check(state.getLateralMode()==ru.octol1ttle.flightassistant.screen.autoflight.AutoFlightScreenState.LateralMode.FLIGHT_PLAN
                        && state.getVerticalMode()==ru.octol1ttle.flightassistant.screen.autoflight.AutoFlightScreenState.VerticalMode.FLIGHT_PLAN
                        && state.getThrustMode()==ru.octol1ttle.flightassistant.screen.autoflight.AutoFlightScreenState.ThrustMode.FLIGHT_PLAN,
                        "All FA selectors show Flight Plan after TMPY");
                check(bus.getAutoflight().getSelectedLateralMode()==null && bus.getAutoflight().getSelectedVerticalMode()==null
                        && bus.getAutoflight().getSelectedThrustMode()==null,"FA genuinely uses its Flight Plan selection");
                check(bus.getPlan().getEnrouteData().size()==4,"Actual FA plan contains two fixes plus T.C/T.D");
                check(bus.getAutoflight().getActiveLateralMode().getControlInput(bus)!=null,"FA NAV mode produces an actual input");
                check(bus.getAutoflight().getActiveThrustMode().getControlInput(bus).getTarget()>0,"FA receives speed demand up to 200 b/s");
                check(bus.getPlan().getArrivalData().isDefault(),"No runway ILS leaves FA APPR empty");
                var parent=client.currentScreen;
                client.setScreen(new ru.octol1ttle.flightassistant.screen.autoflight.AutoFlightScreen(parent));
                client.currentScreen.close();
                dev.airac.compat.FlightAssistantIntegration.tick();
                check(client.currentScreen==parent && dev.airac.compat.FlightAssistantIntegration.active(),
                        "Opening and closing the official FA screen retains EFB plan guidance");
                client.player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST,new net.minecraft.item.ItemStack(net.minecraft.item.Items.ELYTRA));
                client.player.startGliding();
                dev.airac.compat.FlightAssistantIntegration.tick();
                check(bus.getAutoflight().getActiveVerticalMode().getControlInput(bus).getTarget()==30,
                        "Gliding uses configured CLB pitch through the actual FA mode");
            });
            boostedThrust(ctx);
            ctx.runOnClient(client->ru.octol1ttle.flightassistant.impl.computer.ComputerHost.INSTANCE.getAutoflight().setAutoPilot(false,false));
            ctx.waitTick();
            ctx.runOnClient(client->check(!dev.airac.compat.FlightAssistantIntegration.active(),"Manual AP disconnect is never automatically re-engaged"));
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;var bus=ru.octol1ttle.flightassistant.impl.computer.ComputerHost.INSTANCE;
                var previous=new net.minecraft.util.math.Vec3d(client.player.getX(),client.player.getY(),client.player.getZ());
                // Finish an ordinary plan and verify that ownership and AP end together.
                dev.airac.compat.FlightAssistantIntegration.apply(app.chart,app.chart.activeRoute());
                var profile=new dev.airac.nav.PerformanceProfile(app.chart,app.chart.activeRoute(),client.player.getY());
                for(var node:profile.nodes) {
                    client.player.setPosition(node.x(),node.altitude(),node.z());
                    dev.airac.compat.FlightAssistantIntegration.tick();
                }
                check(!dev.airac.compat.FlightAssistantIntegration.active() && !bus.getAutoflight().getAutopilot(),"No-ILS route end disconnects AP");
                client.player.setPosition(previous);
                var airport=new dev.airac.data.ChartData.Point("ILSTEST",dev.airac.data.ChartData.Kind.AIRPORT,previous.x,-60,previous.z-10000);
                app.chart.points.add(airport);
                var runway=new dev.airac.data.ChartData.Runway();runway.airportId=airport.id;runway.name="36/18";
                runway.x1=runway.x2=airport.x;runway.z1=airport.z;runway.z2=airport.z-200;runway.elevation=999;
                runway.ilsEnabled=true;runway.ilsFrequency1=110300;runway.ilsFrequency2=110350;runway.ilsSlope=20;app.chart.runways.add(runway);
                var origin=new dev.airac.data.ChartData.Point("ILSORIGIN",dev.airac.data.ChartData.Kind.WAYPOINT,previous.x,0,previous.z);
                app.chart.points.add(origin);
                var depAirport=new dev.airac.data.ChartData.Point("DEPTEST",dev.airac.data.ChartData.Kind.AIRPORT,previous.x,60,previous.z);
                app.chart.points.add(depAirport);
                var depRunway=new dev.airac.data.ChartData.Runway();depRunway.airportId=depAirport.id;
                depRunway.x1=previous.x;depRunway.z1=previous.z;depRunway.x2=previous.x+200;depRunway.z2=previous.z;
                depRunway.elevation=999;app.chart.runways.add(depRunway);
                var plan=new dev.airac.data.ChartData.Route();plan.pointIds.add(dev.airac.data.ChartData.thresholdId(depRunway.id,"09"));
                plan.departureId=depAirport.id;plan.departureRunwayId=depRunway.id;plan.departureRunwayEnd="09";
                plan.pointIds.add(dev.airac.data.ChartData.thresholdId(runway.id,"36"));
                plan.arrivalId=airport.id;plan.arrivalRunwayId=runway.id;plan.arrivalRunwayEnd="36";
                plan.performance.configured=true;plan.performance.cruiseAltitude=100;plan.performance.descentAltitude=0;
                plan.performance.descentRate=20;plan.performance.speed=100;plan.performance.approachSpeed=24;
                app.chart.routes.add(plan);app.chart.activate(plan);dev.airac.nav.RunwayIls.tuneArrival(app.chart,plan);
                dev.airac.compat.FlightAssistantIntegration.apply(app.chart,plan);
                check(app.chart.radios.getFirst().frequency==110300,"Runway APPR tunes personal receiver");
                var departure=bus.getPlan().getDepartureData();
                check(!departure.isDefault() && departure.getElevation()==60,"FA DEP receives the departure airport elevation");
                var arrival=bus.getPlan().getArrivalData();
                check(!arrival.isDefault() && arrival.getElevation()==-60,"FA APPR uses airport elevation, not runway elevation");
                var points=bus.getPlan().getEnrouteData();
                check(points.getFirst().getAltitude()==60,"First FA waypoint also uses departure airport elevation");
                check(points.getLast().getSpeed()==24,"FA approach waypoint receives APPR SPD");
                dev.airac.compat.FlightAssistantIntegration.stop();
                check(dev.airac.compat.FlightAssistantIntegration.flightPlanMode(bus.getPlan(),0)==null,
                        "Stopping EFB restores ordinary FA plan behavior");
            });
    }
    private static void boostedThrust(ClientGameTestContext ctx) {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var observing=new java.util.concurrent.atomic.AtomicBoolean();
        dev.architectury.event.events.common.InteractionEvent.RIGHT_CLICK_ITEM.register((player,hand)->{
            if(observing.get() && player==net.minecraft.client.MinecraftClient.getInstance().player)requests.incrementAndGet();
            return net.minecraft.util.ActionResult.PASS;
        });
        ctx.runOnClient(client->{
            var bus=ru.octol1ttle.flightassistant.impl.computer.ComputerHost.INSTANCE;
            var player=client.player;var parent=client.currentScreen;
            var position=new net.minecraft.util.math.Vec3d(player.getX(),player.getY(),player.getZ());
            var velocity=player.getVelocity();float pitch=player.getPitch(),yaw=player.getYaw();
            var previous=player.getOffHandStack().copy();
            client.setScreen(null);player.setPosition(position.x,300,position.z);player.setPitch(0);player.setYaw(0);
            player.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND,new net.minecraft.item.ItemStack(net.minecraft.item.Items.FIREWORK_ROCKET,64));
            try {
                observing.set(true);
                for(double speed:new double[]{50,100,180}) {
                    player.age++;player.setVelocity(0,0,speed/20);bus.getData().tick();
                    check(bus.getData().getForwardVelocityPerSecond().length()>34,"Fixture is above official FA rocket ceiling");
                    var source=bus.getThrust().getThrustSource();
                    check(source!=null && source.getClass().getName().equals("dev.airac.compat.FaAutopilot"),
                            "FA arbitrates to the EFB high-speed source");
                    int before=requests.get();bus.getThrust().tick();
                    check(requests.get()==before+1,"FA requests a rocket above 34 b/s at "+speed);
                    bus.getThrust().tick();check(requests.get()==before+1,"No duplicate rocket request in one game tick");
                }
                player.age++;player.setVelocity(0,0,10);bus.getData().tick();
                int before=requests.get();bus.getThrust().tick();
                check(requests.get()==before,"No rocket request at the configured 200 b/s target");
            } finally {
                observing.set(false);player.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND,previous);
                player.setPosition(position);player.setVelocity(velocity);player.setPitch(pitch);player.setYaw(yaw);
                bus.getData().tick();client.setScreen(parent);
            }
        });
    }
}
