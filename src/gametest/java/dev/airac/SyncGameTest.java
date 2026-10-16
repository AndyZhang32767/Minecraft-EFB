package dev.airac;

import dev.airac.data.ChartData;
import dev.airac.sync.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.world.GameMode;
import java.util.Optional;
import java.util.UUID;

/** Real Fabric payload round trips on a LAN-published integrated server. */
public final class SyncGameTest implements FabricClientGameTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @Override public void runTest(ClientGameTestContext ctx) {
        try { runChecks(ctx); }
        catch(java.io.IOException e) { throw new AssertionError("Sync test storage failure",e); }
    }
    private void runChecks(ClientGameTestContext ctx) throws java.io.IOException {
        net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave saved;
        try(var world=ctx.worldBuilder().adjustSettings(c->{c.setWorldName("EFB Sync Test");c.setCheatsEnabled(false);}).create()) {
            saved=world.getWorldSave();
            world.getClientWorld().waitForChunksDownload();ctx.waitTicks(5);
            ctx.runOnClient(client->{
                var player=client.player;var app=AiracClient.INSTANCE;
                player.stopGliding();check(!app.flying(client),"ND FLYING hides while standing");
                boolean flying=player.getAbilities().flying;player.getAbilities().flying=true;
                check(!app.flying(client),"Creative flight does not enable ND FLYING");
                player.getAbilities().flying=flying;
                var pig=new net.minecraft.entity.passive.PigEntity(net.minecraft.entity.EntityType.PIG,client.world);
                check(player.startRiding(pig),"Mount fixture succeeds");
                check(!app.flying(client),"Riding does not enable ND FLYING");player.stopRiding();
                player.startGliding();check(app.flying(client),"Elytra glide enables ND FLYING");player.stopGliding();
            });
            ndDisplayChecks(ctx);
            ctx.runOnClient(client->check(!AiracClient.INSTANCE.sync.shared,"Singleplayer stays local"));
            world.getServer().runOnServer(server->{
                check(server.openToLan(GameMode.CREATIVE,false,0),"Open LAN session");
                var player=server.getPlayerManager().getPlayerList().getFirst();
                server.getPlayerManager().addToOperators(new PlayerConfigEntry(player.getGameProfile()),Optional.empty(),Optional.empty());
            });
            ctx.waitFor(client->AiracClient.INSTANCE.sync.shared && AiracClient.INSTANCE.canEditShared(),400);
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;
                app.chart.points.add(new ChartData.Point("SYNC",ChartData.Kind.WAYPOINT,15,100,20));
                for(int i=0;i<100;i++) {
                    var p=new ChartData.Point("BULK"+i,ChartData.Kind.WAYPOINT,1000+i,100,1000);
                    p.note="n".repeat(240);app.chart.points.add(p);
                }
                check(SharedCharts.encode(app.chart).length>ChunkAssembly.CHUNK_BYTES,"Exercise multiple network fragments");
                app.changed();
                check(app.sync.pending,"Edit must wait for server acknowledgement");
            });
            ctx.waitFor(client->!AiracClient.INSTANCE.sync.pending,200);
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;
                check(app.chart.points.size()==101,"Accepted chart returned to sender");
                var route=new ChartData.Route();route.pointIds.add(app.chart.points.getFirst().id);
                app.chart.routes.add(route);app.chart.activate(route);app.changed();
                check(!app.sync.pending,"Personal plans must never upload");
            });
            // A stale operator update cannot clobber revision 1.
            ctx.waitTicks(12);
            ctx.runOnClient(client->ClientPlayNetworking.send(new ChartPayloads.Upload(AiracClient.INSTANCE.dimension,0,
                UUID.randomUUID(),0,1,SharedCharts.encode(new ChartData()))));
            ctx.waitFor(client->!AiracClient.INSTANCE.error.isEmpty(),100);
            ctx.runOnClient(client->{
                check(AiracClient.INSTANCE.chart.points.size()==101,"Conflict keeps newest server chart");
                check(AiracClient.INSTANCE.chart.routes.size()==1,"Snapshot keeps personal plans");
            });
            ctx.runOnClient(client->{client.options.getGuiScale().setValue(2);client.onResolutionChanged();client.setScreen(new dev.airac.ui.ChartScreen());});
            ctx.getInput().resizeWindow(1280,800);
            ctx.clickScreenButton("ui.airac.tool_waypoint");
            submissionInterval();
            ctx.runOnClient(client->{
                var screen=client.currentScreen;
                var click=new net.minecraft.client.gui.Click(screen.width-70,155,new net.minecraft.client.input.MouseInput(0,0));
                screen.mouseClicked(click,false);screen.mouseReleased(click);
                check(client.currentScreen instanceof dev.airac.ui.ChartScreen,"WPT map click stays in chart");
                check(AiracClient.INSTANCE.chart.points.size()==102,"WPT map click creates waypoint immediately");
                check(screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget).count()==3,
                        "New WPT immediately opens sidebar editing");
            });
            waitForShared(ctx);
            ctx.runOnClient(client->check(client.currentScreen.children().stream()
                    .filter(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget).count()==3,
                    "New WPT sidebar remains open after server acknowledgement"));
            ctx.clickScreenButton("ui.airac.done");
            ctx.runOnClient(client->{
                var screen=client.currentScreen;
                var click=new net.minecraft.client.gui.Click(screen.width-70,155,new net.minecraft.client.input.MouseInput(0,0));
                screen.mouseClicked(click,true);screen.mouseReleased(click);
                check(client.currentScreen instanceof dev.airac.ui.ChartScreen,"Double-click edits WPT in sidebar");
                long fields=screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget).count();
                check(fields==3,"Waypoint sidebar contains only ident and X/Z");
            });
            field(ctx,"ident","SIDEBAR");field(ctx,"x","-10");field(ctx,"z","20");
            ctx.clickScreenButton("ui.airac.save");waitForShared(ctx);
            ctx.runOnClient(client->{
                var p=AiracClient.INSTANCE.chart.points.getLast();
                check(p.ident.equals("SIDEBAR") && p.x==-9.5 && p.z==20.5,"WPT sidebar saves block-centered coordinates");
            });
            ctx.clickScreenButton("ui.airac.delete");ctx.clickScreenButton("ui.airac.confirm_action");waitForShared(ctx);
            ctx.runOnClient(client->check(AiracClient.INSTANCE.chart.points.size()==101,"Sidebar deletion keeps fixture isolated"));
            for(String type:new String[]{"NDB","VOR"}){
                clickLabel(ctx,type);
                waitForShared(ctx);
                ctx.runOnClient(client->{
                    var p=AiracClient.INSTANCE.chart.points.getLast();
                    check(p.kind.name().equals(type),"Create "+type+" from sidebar button");
                    check(p.x==ChartData.blockCenter(client.player.getX()) && p.z==ChartData.blockCenter(client.player.getZ()),"Station at player position");
                    check(dev.airac.nav.RadioNavigation.validFrequency(p.kind,p.frequency),"Random channel is valid");
                });
                field(ctx,"frequency",type.equals("NDB")?"350":type.equals("VOR")?"113.00":"110.30");
                if(type.equals("VOR")){
                    reveal(ctx,"VORDME");clickLabel(ctx,"VORDME"); // cycle to VOR
                    field(ctx,"declination","12.5");
                }
                field(ctx,"radius","1024");
                ctx.clickScreenButton("ui.airac.save");
                waitForShared(ctx);
                ctx.runOnClient(client->{
                    var p=AiracClient.INSTANCE.chart.points.getLast();
                    check(p.radius==1024,"Coverage radius saved");
                    if(type.equals("VOR"))check(p.vorMode==ChartData.VorMode.VOR && p.declination==12.5,"VOR mode and offset saved");
                    check(client.currentScreen instanceof dev.airac.ui.ChartScreen,"Parameters stay in chart sidebar");
                });
            }
            ctx.runOnClient(client->{
                var c=AiracClient.INSTANCE.chart;
                check(SharedCharts.decode(SharedCharts.encode(c)).points.stream().filter(p->dev.airac.nav.RadioNavigation.isStation(p.kind)).count()==2,
                        "NDB and VOR remain public; independent ILS creation is removed");
            });
            runwayIlsSettings(ctx);
            airwayEditing(ctx);
            ctx.clickScreenButton("ui.airac.tool_select");
            ctx.clickScreenButton("ui.airac.tool_airport");
            ctx.clickScreenButton("ui.airac.add_here");
            ctx.waitForScreen(dev.airac.ui.EditorScreen.class);
            world.getServer().runOnServer(server->{
                var player=server.getPlayerManager().getPlayerList().getFirst();
                server.getPlayerManager().removeFromOperators(new PlayerConfigEntry(player.getGameProfile()));
            });
            ctx.waitFor(client->!AiracClient.INSTANCE.sync.editable,100);
            ctx.clickScreenButton("ui.airac.save");
            ctx.waitForScreen(dev.airac.ui.EditorScreen.class);
            ctx.clickScreenButton("ui.airac.cancel");
            ctx.waitForScreen(dev.airac.ui.ChartScreen.class);
            ctx.runOnClient(client->{
                for(var child:client.currentScreen.children())if(child instanceof net.minecraft.client.gui.widget.ButtonWidget b) {
                    if(b.getMessage().equals(dev.airac.ui.Ui.t("edit")))check(!b.active,"Viewer edit button disabled");
                    if(b.getMessage().equals(dev.airac.ui.Ui.t("new_plan")))check(b.active,"Viewer may create personal plans");
                }
            });
            // Exercise the actual personal-plan UI after removing OP, including map DIR entry.
            ctx.clickScreenButton("ui.airac.tool_select");
            ctx.runOnClient(client->{
                var screen=client.currentScreen;
                var click=new net.minecraft.client.gui.Click(40,117,new net.minecraft.client.input.MouseInput(0,0));
                screen.mouseClicked(click,false);screen.mouseReleased(click);
            });
            ctx.clickScreenButton("ui.airac.tool_plan");
            ctx.clickScreenButton("ui.airac.new_plan");
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;
                check(!app.canEditShared() && app.canEditPlans(),"Viewer retains personal-plan permission");
                check(app.chart.routes.size()==2,"Non-OP creates ROUTE through NEW");
                var screen=client.currentScreen;
                int side=screen.width>=560?184:144;
                var click=new net.minecraft.client.gui.Click((side+screen.width)/2.0,(76+screen.height-26)/2.0,
                        new net.minecraft.client.input.MouseInput(0,0));
                screen.mouseClicked(click,false);screen.mouseReleased(click);
                var plan=app.chart.routes.getLast();
                check(plan.pointIds.size()==1,"Non-OP adds a map waypoint to ROUTE");
                check(!app.sync.pending,"Personal ROUTE must not upload");
                check(SharedCharts.decode(SharedCharts.encode(app.chart)).routes.isEmpty(),"Public snapshot excludes ROUTE");
            });
            ctx.clickScreenButton("ui.airac.rename");
            ctx.waitForScreen(dev.airac.ui.EditorScreen.class);
            ctx.runOnClient(client->{
                for(var child:client.currentScreen.children())
                    if(child instanceof net.minecraft.client.gui.widget.TextFieldWidget f)f.setText("VIEWER PRIVATE");
            });
            ctx.clickScreenButton("ui.airac.save");
            ctx.waitForScreen(dev.airac.ui.ChartScreen.class);
            ctx.runOnClient(client->check(AiracClient.INSTANCE.chart.routes.getLast().name.equals("VIEWER PRIVATE"),"Non-OP renames ROUTE"));
            clickLabel(ctx,"F-PLN");clickLabel(ctx,"NEW");
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;
                check(app.chart.routes.size()==3,"Non-OP creates F-PLN through NEW");
                boolean init=false;
                for(var child:client.currentScreen.children())
                    if(child instanceof net.minecraft.client.gui.widget.ButtonWidget b && b.getMessage().getString().equals("INIT"))
                        init=b.active;
                check(init,"Non-OP may edit F-PLN INIT");
                check(!app.sync.pending,"Personal edits never start shared upload");
            });
            ctx.clickScreenButton("ui.airac.tool_radio");
            field(ctx,"RADIO 1 FREQ","350");clickReceiverButton(ctx,"TUNE",0);
            clickReceiverButton(ctx,"NDB",1); // Receiver 2 selects VOR, independently of receiver 1.
            field(ctx,"RADIO 2 FREQ","113.00");clickReceiverButton(ctx,"TUNE",1);
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;var first=app.chart.radios.get(0);var second=app.chart.radios.get(1);
                check(!app.canEditShared() && first.frequency==350 && second.frequency==113000,"Non-OP tunes both personal receivers");
                check(first.mode==ChartData.Kind.NDB && second.mode==ChartData.Kind.VOR,"Receiver modes independent");
                for(var r:app.chart.radios)check(dev.airac.nav.RadioNavigation.receive(app.chart,r,client.player.getX(),client.player.getY(),
                        client.player.getZ(),0)!=null,"Matching station signal received in range");
                check(!app.sync.pending,"Tuning never uploads");
                check(!new String(SharedCharts.encode(app.chart),java.nio.charset.StandardCharsets.UTF_8).contains("radios"),"Public packet omits tuning");
            });
            // Verify compact layout scrolls to both receivers.
            ctx.getInput().resizeWindow(854,480);ctx.waitTick();
            ctx.runOnClient(client->client.currentScreen.mouseScrolled(40,110,0,-8));
            ctx.waitTick();
            ctx.runOnClient(client->{
                boolean visible=false;
                for(var child:client.currentScreen.children())
                    if(child instanceof net.minecraft.client.gui.widget.TextFieldWidget f && f.getMessage().getString().equals("RADIO 2 FREQ"))visible=true;
                check(visible,"Compact RAD/NAV can scroll to receiver 2");
            });
            ctx.getInput().resizeWindow(1280,800);ctx.waitTick();
            performanceAndAutopilot(ctx);
            // Keep the original fixture plan for reconnect/dimension isolation checks.
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;var original=app.chart.routes.getFirst();
                app.chart.routes.removeIf(r->r!=original);app.chart.activate(original);app.planChanged();
                client.setScreen(null);
            });
            ctx.runOnClient(client->{
                check(!AiracClient.INSTANCE.canEditShared(),"De-op disables shared editing");
                check(AiracClient.INSTANCE.writable,"Ordinary players can edit personal flight plans");
            });
            world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 100 0");
            ctx.waitFor(client->AiracClient.INSTANCE.dimension.equals("minecraft:the_nether") && AiracClient.INSTANCE.sync.shared,400);
            ctx.runOnClient(client->{
                check(AiracClient.INSTANCE.chart.points.isEmpty(),"Dimensions have separate charts");
                check(AiracClient.INSTANCE.chart.radios.stream().allMatch(r->r.frequency==0),"Tuning stays dimension-local");
            });
            world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 100 0");
            ctx.waitFor(client->AiracClient.INSTANCE.dimension.equals("minecraft:overworld") && AiracClient.INSTANCE.sync.shared,400);
            ctx.runOnClient(client->{
                check(AiracClient.INSTANCE.chart.points.size()==103,"Returning reloads shared points");
                check(AiracClient.INSTANCE.chart.routes.size()==1,"Returning restores private plans");
                check(AiracClient.INSTANCE.chart.radios.get(0).frequency==350 && AiracClient.INSTANCE.chart.radios.get(1).frequency==113000,
                        "Returning restores personal tuning");
            });
        }
        ctx.waitTicks(5);
        ctx.runOnClient(client->check(!AiracClient.INSTANCE.sync.shared,"Disconnect clears shared session"));
        try(var reopened=saved.open()) {
            reopened.getServer().runOnServer(server->check(server.openToLan(GameMode.CREATIVE,false,0),"Reopen shared world"));
            ctx.waitFor(client->AiracClient.INSTANCE.sync.shared,400);
            ctx.runOnClient(client->{
                check(AiracClient.INSTANCE.chart.points.size()==103,"Server restart restores shared chart");
                check(AiracClient.INSTANCE.chart.routes.size()==1,"Reconnecting restores personal flight plans");
            });
        }
        var sharedPath=new dev.airac.data.ChartStore(saved.getSaveDirectory().resolve("efb"))
            .directory("shared","minecraft:overworld").resolve("chart.json");
        java.nio.file.Files.writeString(sharedPath,"{broken");
        try(var reopened=saved.open()) {
            reopened.getServer().runOnServer(server->check(server.openToLan(GameMode.CREATIVE,false,0),"Open read-only shared world"));
            ctx.waitFor(client->AiracClient.INSTANCE.sync.shared,400);
            ctx.runOnClient(client->{
                check(!AiracClient.INSTANCE.canEditShared(),"Corrupt server storage disables public edits");
                check(AiracClient.INSTANCE.chart.points.size()==103,"Unavailable server chart preserves cached points");
                check(AiracClient.INSTANCE.chart.routes.size()==1,"Unavailable server chart preserves personal plans");
                AiracClient.INSTANCE.changed();
                check(AiracClient.INSTANCE.chart.points.size()==103,"Personal save cannot clear cached points in read-only mode");
            });
        }
        check(java.nio.file.Files.readString(sharedPath).equals("{broken"),"Corrupt original remains untouched");
        compatibility(ctx);
    }
    @SuppressWarnings("unchecked")
    private void compatibility(ClientGameTestContext ctx) {
        // Negotiate a connection with no EFB server channels, as with an unmodified server.
        var request=(net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.PlayPayloadHandler<ChartPayloads.Request>)
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.unregisterGlobalReceiver(ChartPayloads.Request.ID.id());
        var upload=(net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.PlayPayloadHandler<ChartPayloads.Upload>)
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.unregisterGlobalReceiver(ChartPayloads.Upload.ID.id());
        try(var world=ctx.worldBuilder().adjustSettings(c->c.setWorldName("EFB Local Fallback Test")).create()) {
            world.getServer().runOnServer(server->check(server.openToLan(GameMode.CREATIVE,false,0),"Publish fallback world"));
            ctx.waitTicks(40);
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;
                check(!ClientPlayNetworking.canSend(ChartPayloads.Request.ID),"Server does not advertise EFB");
                check(!app.sync.shared && !app.sync.waiting && app.canEditShared(),"No-server-mod fallback remains editable");
                app.chart.points.add(new ChartData.Point("LOCAL",ChartData.Kind.WAYPOINT,0,100,0));app.changed();
                check(!app.sync.pending,"Fallback edits stay local");
            });
            overviewChecks(ctx);
        } finally {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.Request.ID,request);
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.Upload.ID,upload);
        }
        // Remove the receiving channel before joining: server must allow this client to play.
        var receiver=(ClientPlayNetworking.PlayPayloadHandler<ChartPayloads.Snapshot>)
            ClientPlayNetworking.unregisterGlobalReceiver(ChartPayloads.Snapshot.ID.id());
        try(var world=ctx.worldBuilder().adjustSettings(c->c.setWorldName("EFB Optional Client Test")).create()) {
            world.getServer().runOnServer(server->check(server.openToLan(GameMode.CREATIVE,false,0),"Publish optional-client world"));
            ctx.waitTicks(60);
            world.getServer().runOnServer(server->{
                var player=server.getPlayerManager().getPlayerList().getFirst();
                check(!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player.networkHandler,ChartPayloads.Snapshot.ID),
                    "Client must not advertise EFB snapshot support");
                check(!player.isDisconnected(),"A client without EFB support is allowed to stay connected");
            });
            ctx.runOnClient(client->check(client.player!=null && client.world!=null,"Unsupported client can still play"));
        } finally { ClientPlayNetworking.registerGlobalReceiver(ChartPayloads.Snapshot.ID,receiver); }
    }
    private static void overviewChecks(ClientGameTestContext ctx) {
        ctx.runOnClient(client->{
            var app=AiracClient.INSTANCE;double x=client.player.getX(),z=client.player.getZ();
            var fix=new ChartData.Point("LINKED",ChartData.Kind.WAYPOINT,x-500,0,z);
            var vor=dev.airac.nav.RadioNavigation.createStation(app.chart,ChartData.Kind.VOR,x+500,64,z);
            var ndb=dev.airac.nav.RadioNavigation.createStation(app.chart,ChartData.Kind.NDB,x,64,z+500);
            app.chart.points.addAll(java.util.List.of(fix,vor,ndb));
            var airway=new ChartData.Airway();airway.name="OVERVIEW";airway.pointIds.addAll(java.util.List.of(fix.id,vor.id));
            app.chart.airways.add(airway);app.changed();
            client.setScreen(new dev.airac.ui.ChartScreen());
            var screen=client.currentScreen;int side=screen.width>=560?184:144;
            double cx=(side+screen.width)/2.0,cy=(76+screen.height-26)/2.0;
            screen.mouseScrolled(cx,cy,0,-6);
            double scale=.25*Math.pow(1.25,-6);
            var hidden=new net.minecraft.client.gui.Click(cx,cy+500*scale,new net.minecraft.client.input.MouseInput(1,0));
            screen.mouseClicked(hidden,false);screen.mouseReleased(hidden);
            check(screen.children().stream().noneMatch(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget),
                    "Unconnected station cannot be picked in overview");
            var linked=new net.minecraft.client.gui.Click(cx-500*scale,cy,new net.minecraft.client.input.MouseInput(1,0));
            screen.mouseClicked(linked,false);screen.mouseReleased(linked);
            check(screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget).count()==3,
                    "Linked waypoint remains visible and editable in overview");
        });
        ctx.clickScreenButton("ui.airac.done");
        ctx.runOnClient(client->{
            var screen=client.currentScreen;int side=screen.width>=560?184:144;
            double cx=(side+screen.width)/2.0,cy=(76+screen.height-26)/2.0,scale=.25*Math.pow(1.25,-6);
            var linked=new net.minecraft.client.gui.Click(cx+500*scale,cy,new net.minecraft.client.input.MouseInput(1,0));
            screen.mouseClicked(linked,false);screen.mouseReleased(linked);
            check(screen.children().stream().anyMatch(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget f
                    && f.getMessage().equals(dev.airac.ui.Ui.t("frequency"))),"Connected VOR opens configuration in overview");
            screen.mouseScrolled(cx,cy,0,-30);
            check(screen.children().stream().anyMatch(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget f
                    && f.getMessage().equals(dev.airac.ui.Ui.t("frequency"))),"Station configuration survives minimum map scale");
        });
        field(ctx,"declination","90");
        ctx.clickScreenButton("ui.airac.save");
        ctx.runOnClient(client->check(AiracClient.INSTANCE.chart.points.stream()
                .anyMatch(p->p.kind==ChartData.Kind.VOR && p.declination==90),"VOR offset remains editable at minimum scale"));
        ctx.runOnClient(client->{
            var app=AiracClient.INSTANCE;double x=client.player.getX(),z=client.player.getZ();
            var fix=new ChartData.Point("ROUTEONLY",ChartData.Kind.WAYPOINT,x,0,z-500);app.chart.points.add(fix);
            var route=new ChartData.Route();route.pointIds.add(fix.id);app.chart.routes.add(route);app.chart.activate(route);app.changed();
            check(!app.chart.mapConnectedPoints().contains(fix.id),"Single ROUTE point has no airway or ordinary leg");
            client.setScreen(new dev.airac.ui.ChartScreen());
            var screen=client.currentScreen;int side=screen.width>=560?184:144;
            double cx=(side+screen.width)/2.0,cy=(76+screen.height-26)/2.0,scale=.25*Math.pow(1.25,-6);
            screen.mouseScrolled(cx,cy,0,-6);
            var click=new net.minecraft.client.gui.Click(cx,cy-500*scale,new net.minecraft.client.input.MouseInput(1,0));
            screen.mouseClicked(click,false);screen.mouseReleased(click);
            check(screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget).count()==3,
                    "ROUTE-only isolated point remains visible and editable in overview");
        });
    }
    private static void performanceAndAutopilot(ClientGameTestContext ctx) {
        ChartData.Radio previousRadio=new ChartData.Radio();
        ctx.runOnClient(client->{var r=AiracClient.INSTANCE.chart.radios.getFirst();previousRadio.mode=r.mode;previousRadio.frequency=r.frequency;});
        ctx.runOnClient(client->{
            var app=AiracClient.INSTANCE;var route=app.chart.routes.getLast();
            route.pointIds.add(app.chart.points.getFirst().id);route.pointIds.add(app.chart.points.get(100).id);
            route.normalizeVias();app.chart.activate(route);app.planChanged();
            client.setScreen(new dev.airac.ui.ChartScreen());
        });
        ctx.clickScreenButton("ui.airac.tool_plan");
        if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("flightassistant")) {
            ctx.runOnClient(client->{
                check(!dev.airac.compat.FlightAssistantIntegration.available(),"Optional FA is detected as absent");
                check(client.currentScreen.children().stream().noneMatch(c->c instanceof net.minecraft.client.gui.widget.ButtonWidget b
                        && (b.getMessage().equals(dev.airac.ui.Ui.t("performance")) || b.getMessage().equals(dev.airac.ui.Ui.t("apply_plan")))),
                        "No-FA ROUTE hides performance and apply");
            });
            clickLabel(ctx,"F-PLN");
            ctx.runOnClient(client->check(client.currentScreen.children().stream().noneMatch(c->c instanceof net.minecraft.client.gui.widget.ButtonWidget b
                    && (b.getMessage().getString().equals("PERF") || b.getMessage().getString().equals("TMPY"))),
                    "No-FA F-PLN hides PERF and TMPY"));
            check(ctx.tryClickScreenButton("INIT"),"No-FA F-PLN still supports INIT");
            clickLabel(ctx,"<RETURN");return;
        }
        clickLabel(ctx,"F-PLN");clickLabel(ctx,"PERF");
        ctx.waitForScreen(dev.airac.ui.PerformanceScreen.class);
        field(ctx,"TO PITCH / DEG","30");field(ctx,"CRZ ALT / B","100");field(ctx,"TGT SPD / B/S","200");
        field(ctx,"DES V/S / B/S","100");field(ctx,"DES ALT / B","-60");field(ctx,"ILS FREQ / MHZ","110.30");
        field(ctx,"APPR SPD / B/S","24");
        clickLabel(ctx,"INSERT*");
        ctx.runOnClient(client->{
            var app=AiracClient.INSTANCE;var route=app.chart.activeRoute();
            check(route.performance.configured && route.performance.speed==200,"PERF saved to personal plan");
            check(route.performance.approachSpeed==24 && route.performance.ilsFrequency==0,"APPR SPD saved; no runway ILS leaves APPR frequency empty");
            var profile=new dev.airac.nav.PerformanceProfile(app.chart,route,client.player.getY());
            check(profile.feasible && profile.markers.size()==2,"T.C and T.D calculated from PERF");
            check(client.currentScreen instanceof dev.airac.ui.ChartScreen,"Saving PERF returns to F-PLN");
            check(!app.sync.pending,"PERF never uploads");
        });
        FaIntegrationChecks.run(ctx);
        ctx.runOnClient(client->{
            var app=AiracClient.INSTANCE;var r=app.chart.radios.getFirst();
            r.mode=previousRadio.mode;r.frequency=previousRadio.frequency;app.save();
        });
    }
    private static void ndDisplayChecks(ClientGameTestContext ctx) {
        var previous=ctx.computeOnClient(client->AiracClient.INSTANCE.settings);
        int guiScale=ctx.computeOnClient(client->client.options.getGuiScale().getValue());
        ctx.getInput().resizeWindow(1280,800);
        ctx.runOnClient(client->{
            var settings=new dev.airac.data.NdSettings();settings.visibility=dev.airac.data.NdSettings.Visibility.ALWAYS;
            settings.rangeIndex=2;AiracClient.INSTANCE.settings=settings;
            client.options.getGuiScale().setValue(4);client.onResolutionChanged();
        });
        try {
            ctx.runOnClient(client->AiracClient.INSTANCE.settings.rangeIndex=0);
            for(int range:new int[]{256,512,1024,2048,4096,8192,8192}) {
                ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP);
                ctx.runOnClient(client->check(AiracClient.INSTANCE.settings.range()==range,"PgUp selects "+range));
            }
            for(int range:new int[]{4096,2048,1024,512,256,128,128}) {
                ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN);
                ctx.runOnClient(client->check(AiracClient.INSTANCE.settings.range()==range,"PgDn selects "+range));
            }
            int autoRange=ctx.computeOnClient(client->{AiracClient.INSTANCE.settings.autoRange=true;
                return AiracClient.INSTANCE.settings.rangeAtAltitude(client.player.getY());});
            ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP);
            ctx.runOnClient(client->check(!AiracClient.INSTANCE.settings.autoRange && AiracClient.INSTANCE.settings.range()==autoRange*2,
                    "PgUp switches from automatic range to the next manual range"));
            ctx.runOnClient(client->AiracClient.INSTANCE.settings.rangeIndex=2);
            for(int size:new int[]{140,120,100}) {
                ctx.runOnClient(client->AiracClient.INSTANCE.settings.size=size);
                ctx.waitTick();ctx.takeScreenshot("nd-arc-"+size);
            }
            ctx.runOnClient(client->AiracClient.INSTANCE.settings.mode=dev.airac.data.NdSettings.Mode.NAV);
            ctx.waitTick();ctx.takeScreenshot("nd-nav-100");
        } finally {
            ctx.runOnClient(client->{
                AiracClient.INSTANCE.settings=previous;AiracClient.INSTANCE.saveSettings();
                client.options.getGuiScale().setValue(guiScale);client.onResolutionChanged();
            });
        }
    }
    private static void runwayIlsSettings(ClientGameTestContext ctx) {
        ctx.runOnClient(client->{
            var app=AiracClient.INSTANCE;
            var airport=new ChartData.Point("RWYTEST",ChartData.Kind.AIRPORT,1000,80,1000);app.chart.points.add(airport);
            var runway=new ChartData.Runway();runway.airportId=airport.id;runway.x1=1000;runway.x2=1200;runway.z1=runway.z2=1000;
            app.chart.runways.add(runway);
            ((dev.airac.ui.ChartScreen)client.currentScreen).editAirportRunway(runway,client.currentScreen);
            check(client.currentScreen.children().stream().noneMatch(c->c instanceof net.minecraft.client.gui.widget.TextFieldWidget f
                    && f.getMessage().equals(dev.airac.ui.Ui.t("ils_slope"))),"ILS parameters hidden while disabled");
        });
        ctx.clickScreenButton("ui.airac.off");
        field(ctx,"ils_slope","25");field(ctx,"ils_frequency1","110.30");field(ctx,"ils_frequency2","110.35");
        ctx.clickScreenButton("ui.airac.save");waitForShared(ctx);
        ctx.runOnClient(client->{
            var c=AiracClient.INSTANCE.chart;var runway=c.runways.getLast();
            check(runway.ilsEnabled && runway.ilsSlope==25,"Runway ILS toggle and ratio save from settings");
            check(dev.airac.nav.RunwayIls.station(c,runway,"09").y==80,"Derived ILS uses airport elevation");
            c.removePoint(runway.airportId);AiracClient.INSTANCE.changed();
            client.setScreen(new dev.airac.ui.ChartScreen());
        });
        waitForShared(ctx);
    }
    private static void airwayEditing(ClientGameTestContext ctx) {
        var ids=new java.util.ArrayList<String>();
        ctx.runOnClient(client->{
            var app=AiracClient.INSTANCE;double x=client.player.getX(),z=client.player.getZ();
            double[] dx={-200,-100,0,100,0,200},dz={0,0,0,0,100,0};
            for(int i=0;i<6;i++) {
                var p=new ChartData.Point("EDITTEST"+i,ChartData.Kind.WAYPOINT,x+dx[i],0,z+dz[i]);
                ids.add(p.id);app.chart.points.add(p);
            }
            var a=new ChartData.Airway();a.name="EDITTEST";a.oneWay=true;a.pointIds.addAll(ids.subList(1,4));
            app.chart.airways.add(a);app.changed();client.setScreen(new dev.airac.ui.ChartScreen());
        });
        waitForShared(ctx);ctx.clickScreenButton("ui.airac.tool_airway");
        chartTap(ctx,15,116); // Select the network airway and open its waypoint list.
        chartTap(ctx,15,136); // Head +.
        airwayMapTap(ctx,-50,0);waitForShared(ctx);
        chartTap(ctx,15,184); // Select the original middle fix after prepending.
        ctx.clickScreenButton("ui.airac.remove");waitForShared(ctx);
        ctx.runOnClient(client->{
            var a=AiracClient.INSTANCE.chart.airways.getLast();
            check(a.disconnectedBefore(2),"Deleting airway WPT leaves a gap");
            check(AiracClient.INSTANCE.chart.point(ids.get(2))!=null,"Deleting airway WPT preserves public fix");
        });
        chartTap(ctx,15,184); // Gap +.
        airwayMapTap(ctx,0,25);waitForShared(ctx);
        airwayMapTap(ctx,25,0);waitForShared(ctx); // Join original downstream fix.
        chartTap(ctx,15,216); // Tail + after gap is joined.
        airwayMapTap(ctx,50,0);waitForShared(ctx);
        ctx.runOnClient(client->{
            var c=AiracClient.INSTANCE.chart;var a=c.airways.getLast();
            check(a.pointIds.equals(java.util.List.of(ids.get(0),ids.get(1),ids.get(4),ids.get(3),ids.get(5))),
                    "Head, gap and tail + controls insert actual map picks in order");
            check(!a.discontinuities.contains(true),"Clicking downstream fix closes the airway gap");
            check(dev.airac.nav.RoutePlanner.airwayPath(c,"EDITTEST",ids.get(0),ids.get(5)).equals(a.pointIds),
                    "F-PLN sees the edited directed airway");
            c.airways.remove(a);for(String id:ids)c.removePoint(id);AiracClient.INSTANCE.changed();
            client.setScreen(new dev.airac.ui.ChartScreen());
        });
        waitForShared(ctx);
    }
    private static void chartTap(ClientGameTestContext ctx,double x,double y) {
        ctx.runOnClient(client->{
            var click=new net.minecraft.client.gui.Click(x,y,new net.minecraft.client.input.MouseInput(0,0));
            client.currentScreen.mouseClicked(click,false);client.currentScreen.mouseReleased(click);
        });
    }
    private static void airwayMapTap(ClientGameTestContext ctx,double dx,double dy) {
        ctx.runOnClient(client->{
            var screen=client.currentScreen;int side=screen.width>=560?184:144;
            double x=(side+screen.width)/2.0+dx,y=(76+screen.height-26)/2.0+dy;
            var click=new net.minecraft.client.gui.Click(x,y,new net.minecraft.client.input.MouseInput(0,0));
            screen.mouseClicked(click,false);screen.mouseReleased(click);
        });
    }
    private static void waitForShared(ClientGameTestContext ctx) {
        try { ctx.waitFor(client->AiracClient.INSTANCE.canEditShared(),200); }
        catch(AssertionError failure) {
            ctx.runOnClient(client->{
                var app=AiracClient.INSTANCE;var sync=app.sync;
                throw new AssertionError("Shared edit timeout: shared="+sync.shared+" pending="+sync.pending+" waiting="+sync.waiting
                        +" editable="+sync.editable+" writable="+app.writable+" points="+app.chart.points.size()+" error="+app.error,failure);
            });
        }
        submissionInterval();
    }
    private static void submissionInterval() {
        // Gametest ticks run faster than wall time; respect the public upload rate limit.
        try { Thread.sleep(120); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
    }
    private static void clickLabel(ClientGameTestContext ctx,String label) {
        ctx.runOnClient(client->{
            for(var child:client.currentScreen.children())
                if(child instanceof net.minecraft.client.gui.widget.ButtonWidget b && b.getMessage().getString().equals(label)){
                    check(b.active,"Button enabled: "+label);
                    var click=new net.minecraft.client.gui.Click(b.getX()+b.getWidth()/2.0,b.getY()+8,
                            new net.minecraft.client.input.MouseInput(0,0));
                    client.currentScreen.mouseClicked(click,false);return;
                }
            throw new AssertionError("Missing button: "+label);
        });
    }
    private static void field(ClientGameTestContext ctx,String key,String value) {
        boolean[] found={false};
        for(int attempt=0;attempt<12 && !found[0];attempt++){
            ctx.runOnClient(client->{
                for(var child:client.currentScreen.children())
                    if(child instanceof net.minecraft.client.gui.widget.TextFieldWidget f
                            && (f.getMessage().equals(dev.airac.ui.Ui.t(key)) || f.getMessage().getString().equals(key))){
                        f.setText(value);found[0]=true;return;
                    }
                client.currentScreen.mouseScrolled(40,170,0,-1);
            });
        }
        check(found[0],"Field visible: "+key);
    }
    private static void reveal(ClientGameTestContext ctx,String label) {
        boolean[] found={false};
        for(int attempt=0;attempt<12 && !found[0];attempt++){
            ctx.runOnClient(client->{
                for(var child:client.currentScreen.children())
                    if(child instanceof net.minecraft.client.gui.widget.ButtonWidget b && b.getMessage().getString().equals(label)){found[0]=true;return;}
                client.currentScreen.mouseScrolled(40,170,0,-1);
            });
        }
        check(found[0],"Button visible: "+label);
    }
    private static void clickReceiverButton(ClientGameTestContext ctx,String label,int receiver) {
        ctx.runOnClient(client->{
            int found=0;
            for(var child:client.currentScreen.children())
                if(child instanceof net.minecraft.client.gui.widget.ButtonWidget b && b.getMessage().getString().equals(label)){
                    if(found++ != receiver)continue;
                    check(b.active,"Receiver control active");
                    var click=new net.minecraft.client.gui.Click(b.getX()+b.getWidth()/2.0,b.getY()+8,
                            new net.minecraft.client.input.MouseInput(0,0));client.currentScreen.mouseClicked(click,false);return;
                }
            throw new AssertionError("Receiver button missing: "+label+" "+receiver);
        });
    }
}
