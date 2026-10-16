package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import dev.airac.nav.NavMath;
import dev.airac.nav.RoutePlanner;
import dev.airac.nav.PerformanceProfile;
import dev.airac.compat.FlightAssistantIntegration;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.Consumer;

public final class ChartScreen extends Screen {
    private enum Tool { SELECT, WAYPOINT, AIRPORT, RUNWAY, AIRWAY, PLAN, RADIO }
    private static final Tool[] TOOLBAR={Tool.SELECT,Tool.WAYPOINT,Tool.AIRPORT,Tool.AIRWAY,Tool.PLAN,Tool.RADIO};
    private Screen airportParent;
    final ChartSidebar sidebar=new ChartSidebar(this);
    final PointSidebar pointsEditor=new PointSidebar(this);
    final RadioSidebar radios=new RadioSidebar(this);
    final AirwaySidebar airwayEditor=new AirwaySidebar(this);
    boolean fpln;
    String selectedProcedure;
    private final AiracClient app=AiracClient.INSTANCE;
    private Tool tool=Tool.SELECT;
    String selectedPoint,selectedRunway,selectedAirway,selectedPlan,airwayFrom,buildingAirway;
    private NavMath.Vec runwayStart;
    int selectedLeg=-1,scroll;
    int side,mapLeft,mapTop=76,mapRight,mapBottom,listTop=114,listBottom;
    private double centerX,centerZ,scale=0.25,clickX,clickY;
    private boolean centered,mapHeld,dragged;
    private String hint="",searchQuery="";
    private Route previewRoute;
    private long previewEpoch=-1,previewRevision=-1;
    private PerformanceProfile previewProfile;

    public ChartScreen() { super(Text.literal("EFB"));selectedPlan=app.chart.activeRouteId; }
    private ChartData data() { return app.chart; }
    private ButtonWidget button(int x,int y,int w,String key,Runnable action) {
        var b=new FlatButton(x,y,w,18,Ui.t(key),ignored->action.run());
        addDrawableChild(b);return b;
    }
    void refresh() { scroll=Math.max(0,scroll);clearAndInit(); }
    @Override protected void init() {
        if(tool==Tool.PLAN && !Objects.equals(selectedPlan,data().activeRouteId)) {
            if(plan()==null && data().activeRouteId!=null){selectedPlan=data().activeRouteId;selectedLeg=-1;}
        }
        listTop=tool==Tool.PLAN?134:114;
        side=width>=560?184:144;mapLeft=side+8;mapRight=width-8;mapBottom=height-26;listBottom=height-110;
        if(!centered && client!=null && client.player!=null) { centerPlayer();centered=true; }
        int bw=(width-22)/TOOLBAR.length;
        for(int toolIndex=0;toolIndex<TOOLBAR.length;toolIndex++) {
            Tool value=TOOLBAR[toolIndex];
            var b=button(8+toolIndex*(bw+1),25,bw,"tool_"+value.name().toLowerCase(Locale.ROOT),()->setTool(value));
            b.active=value==Tool.SELECT || value==Tool.WAYPOINT || value==Tool.AIRWAY || value==Tool.AIRPORT || (value==Tool.PLAN || value==Tool.RADIO?app.canEditPlans():app.canEditShared());
        }
        boolean find=tool!=Tool.PLAN && tool!=Tool.RADIO;
        var search=new TextFieldWidget(textRenderer,5,47,side-5,18,Ui.t("search"));
        search.setMaxLength(64);search.setText(searchQuery);search.setPlaceholder(Ui.t("search"));
        search.setEditable(find);search.active=find;search.setChangedListener(v->{searchQuery=v;scroll=0;});
        addDrawableChild(search);
        int gap=4,bx=side+8,span=Math.max(28,(width-8-bx-gap*2)/3);
        FlatButton basemap=(FlatButton)button(bx,47,span,"basemap",()->{app.settings.terrain=!app.settings.terrain;app.saveSettings();refresh();});
        basemap.color(app.settings.terrain?Ui.CYAN:Ui.TEXT);
        basemap.setTooltip(Tooltip.of(Ui.t(app.settings.terrain?"satellite":"solid")));
        button(bx+span+gap,47,span,"center",this::centerPlayer);
        button(bx+2*(span+gap),47,span,"nd_settings",()->client.setScreen(new SettingsScreen(this)));
        if(tool==Tool.PLAN) {
            button(8,80,26,"previous",()->cyclePlan(-1));
            button(side-34,80,26,"next",()->cyclePlan(1));
            int modeWidth=(side-19)/2;
            addDrawableChild(new FlatButton(8,110,modeWidth,18,Ui.t("route_mode"),b->{fpln=false;sidebar.reset();refresh();}));
            addDrawableChild(new FlatButton(11+modeWidth,110,modeWidth,18,Text.literal("F-PLN"),b->{if(plan()==null)newPlan();fpln=true;sidebar.reset();refresh();}));
        }
        if(tool==Tool.RADIO){radios.init();return;}
        if(tool==Tool.WAYPOINT){
            int buttonWidth=(side-19)/2;
            Kind[] kinds={Kind.NDB,Kind.VOR};
            for(int i=0;i<kinds.length;i++){
                Kind kind=kinds[i];
                addDrawableChild(new FlatButton(8+i*(buttonWidth+3),110,buttonWidth,18,Text.literal(kind.name()),b->addStation(kind))).active=app.canEditShared();
            }
            listTop=134;
        }
        if(pointsEditor.active()){pointsEditor.init();return;}
        if(sidebar.active()){sidebar.init();return;}
        if(airwayEditor.active()){airwayEditor.init();return;}
        int y=height-104,w=side-16,half=(w-3)/2;
        if(tool==Tool.PLAN) {
            button(8,y,half,"up",()->moveLeg(-1));
            button(11+half,y,half,"down",()->moveLeg(1));
            button(8,y+20,half,"rename",this::renamePlan);
            button(11+half,y+20,half,"remove",this::removeLeg);
            button(8,y+40,half,"delete_plan",()->confirm("confirm_delete_plan",()->{
                var p=plan();if(p!=null){data().routes.remove(p);if(p.id.equals(data().activeRouteId))data().deactivate();selectedPlan=null;selectedLeg=-1;changed();}
            }));
            button(11+half,y+40,half,"new_plan",this::newPlan);
            if(FlightAssistantIntegration.available()) {
                button(8,y+60,half,"performance",this::performance);
                button(11+half,y+60,half,"apply_plan",this::applyPlan);
            }
        } else if(tool==Tool.AIRWAY) {
            button(8,y,w,"edit",this::editSelectedAirway);
            button(8,y+20,w,"delete",()->confirm("confirm_delete",()->{data().airways.removeIf(a->a.id.equals(selectedAirway));selectedAirway=null;changed();}));
            button(8,y+40,w,"clear_airways",()->confirm("confirm_clear_airways",()->{data().clearAirways();selectedAirway=null;changed();}));
            button(8,y+60,w,"finish_airway",()->{airwayFrom=buildingAirway=null;hint="";});
        } else if(tool==Tool.RUNWAY) {
            button(8,y,w,"edit",this::editSelectedRunway);
            button(8,y+20,w,"delete",()->confirm("confirm_delete",()->{data().removeRunway(selectedRunway);selectedRunway=null;changed();}));
            button(8,y+40,w,"cancel_pick",()->{runwayStart=null;hint="";tool=Tool.AIRPORT;if(airportParent!=null)client.setScreen(airportParent);});
        } else {
            button(8,y,w,"edit",()->{var p=data().point(selectedPoint);if(p!=null){if(p.kind==Kind.AIRPORT)openAirport(p.id);else editPoint(p,false);}});
            button(8,y+20,w,"delete",()->confirm("confirm_delete_point",()->{
                if(selectedPoint!=null){data().removePoint(selectedPoint);selectedPoint=null;changed();}
            }));
            button(8,y+40,w,"locate",()->{var p=data().point(selectedPoint);if(p!=null){centerX=p.x;centerZ=p.z;}});
            button(8,y+60,w,"add_here",()->{
                if(client.player!=null)editPoint(new Point(nextIdent(tool==Tool.AIRPORT?"APT":"FIX"),
                        tool==Tool.AIRPORT?Kind.AIRPORT:Kind.WAYPOINT,client.player.getX(),client.player.getY(),client.player.getZ()),true);
            });
        }
        for(var child:children())if(child instanceof ButtonWidget b && b.getY()>=80) {
            boolean navigation=b.getMessage().equals(Ui.t("edit")) && data().point(selectedPoint)!=null && data().point(selectedPoint).kind==Kind.AIRPORT || b.getMessage().equals(Ui.t("locate")) || b.getMessage().equals(Ui.t("cancel_pick")) || b.getMessage().equals(Ui.t("finish_airway"));
            if(!navigation)b.active=tool==Tool.PLAN?app.canEditPlans():app.canEditShared();
        }
    }
    public void sharedChartUpdated() {
        if(!app.canEditShared() && (tool==Tool.WAYPOINT || tool==Tool.RUNWAY))tool=Tool.SELECT;
        sidebar.updated();pointsEditor.updated();airwayEditor.updated();
        // Keep chaining after our own server acknowledgement; discard stale deleted picks.
        if(data().point(airwayFrom)==null)airwayFrom=null;
        if(data().airways.stream().noneMatch(a->a.id.equals(buildingAirway)))buildingAirway=null;
        runwayStart=null;
        if(data().point(selectedPoint)==null)selectedPoint=null;
        refresh();
    }
    @Override public void tick() {
        boolean editable=app.canEditShared();
        if(editable!=lastEditable){lastEditable=editable;refresh();}
    }
    private boolean lastEditable=true;
    private void setTool(Tool value) {
        sidebar.reset();pointsEditor.reset();radios.reset();airwayEditor.reset();fpln=false;selectedProcedure=null;
        tool=value;airwayFrom=buildingAirway=null;runwayStart=null;scroll=0;hint="";refresh();
        if(tool==Tool.PLAN) {
            if(plan()==null && !data().routes.isEmpty()) selectPlan(data().routes.getFirst());
            if(plan()==null) newPlan();

        }
    }
    private void changed() { if(tool==Tool.PLAN)app.planChanged();else app.changed();refresh(); }
    private void centerPlayer() { if(client!=null && client.player!=null){centerX=client.player.getX();centerZ=client.player.getZ();} }
    Route plan() { return data().route(selectedPlan); }
    private String nextIdent(String prefix) {
        int n=data().points.size()+1;
        while(true) {String id=prefix+n++;if(data().points.stream().noneMatch(p->p.ident.equalsIgnoreCase(id)))return id;}
    }
    void confirm(String key,Runnable action) {
        boolean shared=tool!=Tool.PLAN;
        if(shared?!app.canEditShared():!app.canEditPlans())return;
        long epoch=app.chartEpoch;
        client.setScreen(new ConfirmScreen(yes->{
            if(yes && epoch!=app.chartEpoch)app.syncError("conflict");
            else if(yes && (shared?app.canEditShared():app.canEditPlans()))action.run();
            client.setScreen(this);
        },
                Ui.t("confirm"),Ui.t(key),Ui.t("confirm_action"),Ui.t("cancel")));
    }
    private void form(String title,List<EditorScreen.Field> fields,Consumer<Map<String,String>> save) {
        long epoch=app.chartEpoch;
        if(app.canEditShared())client.setScreen(new EditorScreen(this,title,fields,v->{
            if(epoch!=app.chartEpoch)throw new IllegalArgumentException(Ui.tr("ui.airac.sync_conflict"));
            if(!app.canEditShared())throw new IllegalArgumentException(Ui.tr("ui.airac.sync_forbidden"));
            save.accept(v);
        }));
    }
    private void planForm(String title,List<EditorScreen.Field> fields,Consumer<Map<String,String>> save) {
        long epoch=app.chartEpoch;
        if(app.canEditPlans())client.setScreen(new EditorScreen(this,title,fields,v->{
            if(epoch!=app.chartEpoch)throw new IllegalArgumentException(Ui.tr("ui.airac.sync_conflict"));
            if(!app.canEditPlans())throw new IllegalArgumentException(Ui.tr("ui.airac.sync_read_only"));
            save.accept(v);
        }));
    }
    private static EditorScreen.Field field(String key,Object value) { return new EditorScreen.Field(key,value instanceof Number n
                ? String.valueOf((long)Math.floor(n.doubleValue())) : String.valueOf(value)); }
    private void editPoint(Point p,boolean fresh) {
        if(p.kind==Kind.WAYPOINT || dev.airac.nav.RadioNavigation.isStation(p.kind)){
            if(fresh){if(!app.canEditShared())return;data().points.add(p);app.changed();}
            pointsEditor.open(p);return;
        }
        List<EditorScreen.Field> fields=p.kind==Kind.WAYPOINT
                ?List.of(field("ident",p.ident),field("x",p.x),field("z",p.z))
                :List.of(field("ident",p.ident),field("name",p.name),field("x",p.x),field("z",p.z),field("elevation",p.y),field("note",p.note));
        form(fresh?"new_point":"edit_point",fields,v->{
            String ident=EditorScreen.required(v,"ident").toUpperCase(Locale.ROOT);
            if(data().identifierUsed(ident,p.kind,p.id)) throw new IllegalArgumentException(Ui.tr("ui.airac.duplicate"));
            double x=ChartData.blockCenter(EditorScreen.number(v,"x")),z=ChartData.blockCenter(EditorScreen.number(v,"z"));
            double y=p.kind==Kind.AIRPORT?EditorScreen.number(v,"elevation"):0;
            p.ident=ident;p.name=v.getOrDefault("name","");p.note=v.getOrDefault("note",p.note);p.x=x;p.y=y;p.z=z;
            if(fresh)data().points.add(p);selectedPoint=p.id;app.changed();
        });
    }
    void newPlan() {
        if(!app.canEditPlans())return;
        sidebar.reset();tool=Tool.PLAN;
        Route route=new Route();route.name="ROUTE "+(data().routes.size()+1);
        data().routes.add(route);selectedPlan=route.id;selectedLeg=-1;scroll=0;
        data().activeRouteId=route.id;data().activeLeg=0;app.planChanged();refresh();
    }
    void performance() { if(FlightAssistantIntegration.available())client.setScreen(new PerformanceScreen(this, fpln, plan())); }
    void applyPlan() {
        if(!FlightAssistantIntegration.available())return;
        Route p=plan(); if(p==null || !app.canEditPlans())return;
        if(!p.id.equals(data().activeRouteId))data().activate(p);
        app.planChanged();
        if(p.performance.configured) {
            try {
                FlightAssistantIntegration.apply(data(),p);app.save();
                hint=fpln?"F-PLN INSERTED / AP + A/THR ENGAGED":Ui.tr("ui.airac.fa_engaged");
            } catch(IllegalArgumentException ex) {hint=fpln?faMessage(ex.getMessage()):Ui.tr("ui.airac."+ex.getMessage());}
        }
        sidebar.reset();refresh();
    }
    private String faMessage(String key) {return switch(key){
        case "fa_unavailable"->"F-PLN APPLIED / FA NOT INSTALLED";
        case "fa_not_ready"->"FA NOT READY - CHECK SYSTEMS";
        case "perf_discontinuity"->"F-PLN DISCONTINUITY";
        case "perf_short_route"->"INSUFFICIENT DISTANCE FOR PERF";
        case "perf_route_required"->"AT LEAST TWO WAYPOINTS REQUIRED";
        default->"FA IMPORT FAILED - CHECK PERF";
    };}
    private void renamePlan() {
        Route p=plan();if(p==null)return;
        planForm("rename",List.of(field("name",p.name)),v->{p.name=EditorScreen.required(v,"name");app.planChanged();});
    }
    private void selectPlan(Route p) {
        sidebar.reset();selectedPlan=p.id;selectedLeg=p.pointIds.size()-1;scroll=0;data().activate(p);app.planChanged();refresh();
    }
    private void cyclePlan(int step) {
        if(data().routes.isEmpty())return;
        selectPlan(data().routes.get(Math.floorMod(data().routes.indexOf(plan())+step,data().routes.size())));
    }
    private void moveLeg(int step) {
        Route p=plan();if(p==null)return;
        selectedLeg=RoutePlanner.move(data(),p,selectedLeg,step);changed();
    }
    private void removeLeg() {
        Route p=plan();if(p==null)return;
        selectedLeg=RoutePlanner.remove(data(),p,selectedLeg);changed();
    }
    private void appendPlan(Point point) {
        Route p=plan();if(p==null){newPlan();return;}
        try {
            selectedLeg=RoutePlanner.direct(data(),p,p.editAfter,point.id);app.planChanged();
            scroll=Math.max(0,RouteRows.of(p).indexOf(new RouteRows.Row(RouteRows.Kind.FIX,selectedLeg))-Math.max(1,(listBottom-listTop)/16)+1);
        } catch(IllegalArgumentException e){hint=Ui.tr("ui.airac."+e.getMessage());}
    }
    void editSelectedAirway() { data().airways.stream().filter(a->a.id.equals(selectedAirway)).findFirst().ifPresent(a->editAirway(a,false)); }
    boolean isAirway(){return tool==Tool.AIRWAY;}
    void beginAirway(){airwayFrom=buildingAirway=selectedAirway=null;scroll=0;hint="";refresh();}
    void airwayHint(String key){hint=Ui.tr("ui.airac."+key);}
    private void editAirway(Airway a,boolean fresh) {
        String fixes=String.join(" ",a.pointIds.stream().map(id->data().point(id).ident).toList());
        form("edit_airway",List.of(field("name",a.name),field("fixes",fixes),new EditorScreen.Field("one_way",a.oneWay?"on":"off",List.of("off","on"))),v->{
            String name=EditorScreen.required(v,"name").toUpperCase(Locale.ROOT);
            List<String> ids=new ArrayList<>();
            for(String ident:EditorScreen.required(v,"fixes").split("[\\s,]+")) {
                Point point=byIdent(ident);
                if(point==null || !ids.isEmpty() && ids.getLast().equals(point.id))
                    throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_endpoints"));
                ids.add(point.id);
            }
            if(ids.size()<(fresh?2:1) || ids.size()>2048)throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_endpoints"));
            if(data().airways.stream().anyMatch(b->b!=a && b.name.equalsIgnoreCase(name)
                    && (b.pointIds.equals(ids) || b.pointIds.reversed().equals(ids))))
                throw new IllegalArgumentException(Ui.tr("ui.airac.duplicate_airway"));
            if(!a.pointIds.equals(ids))a.discontinuities.clear();
            a.name=name;a.pointIds=new ArrayList<>(ids);a.oneWay="on".equals(v.get("one_way"));a.normalizeGaps();
            if(fresh)data().airways.add(a);
            selectedAirway=a.id;
            if(fresh || a.id.equals(buildingAirway)) {buildingAirway=a.id;airwayFrom=ids.getLast();hint=Ui.tr("ui.airac.continue_airway");}
            airwayEditor.open(a,fresh);
            app.changed();
        });
    }
    private Point byIdent(String ident) { return RoutePlanner.byIdent(data(),ident); }
    public void startRunway(String airportId,Screen parent) {
        airportParent=this;selectedPoint=airportId;tool=Tool.RUNWAY;runwayStart=null;hint=Ui.tr("ui.airac.pick_threshold");
        client.setScreen(this);
    }
    public void editAirportRunway(Runway runway,Screen parent) {airportParent=parent;editRunway(runway,false);}
    public void editAirport(String id,Screen parent) {
        Point point=data().point(id);if(point==null)return;editPoint(point,false);
        if(client.currentScreen instanceof EditorScreen editor)editor.returnTo(parent);
    }
    private List<Runway> visibleRunways() {return data().runways.stream().filter(r->Objects.equals(r.airportId,selectedPoint)).toList();}
    void editSelectedRunway() { data().runways.stream().filter(r->r.id.equals(selectedRunway)).findFirst().ifPresent(r->editRunway(r,false)); }
    private void editRunway(Runway r,boolean fresh) {
        Runway channels=new Runway();channels.id=r.id;channels.ilsFrequency1=r.ilsFrequency1;channels.ilsFrequency2=r.ilsFrequency2;
        dev.airac.nav.RunwayIls.assignFrequencies(data(),channels);
        form("edit_runway",List.of(field("name",r.name),field("airport",data().point(r.airportId).ident),
                field("x1",r.x1),field("z1",r.z1),field("x2",r.x2),field("z2",r.z2),
                field("elevation",r.elevation),field("width",r.width),
                new EditorScreen.Field("ils_enabled",r.ilsEnabled?"on":"off",List.of("off","on")),
                field("ils_slope",Double.toString(r.ilsSlope)),
                field("ils_frequency1",dev.airac.nav.RadioNavigation.frequency(Kind.ILS,channels.ilsFrequency1)),
                field("ils_frequency2",dev.airac.nav.RadioNavigation.frequency(Kind.ILS,channels.ilsFrequency2))),v->{
            String name=EditorScreen.required(v,"name").toUpperCase(Locale.ROOT).replace(" ","");Point airport=RoutePlanner.airportByIdent(data(),EditorScreen.required(v,"airport"));
            if(airport==null || airport.kind!=Kind.AIRPORT)throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_airport"));
            double x1=ChartData.blockCenter(EditorScreen.number(v,"x1")),z1=ChartData.blockCenter(EditorScreen.number(v,"z1")),x2=ChartData.blockCenter(EditorScreen.number(v,"x2")),z2=ChartData.blockCenter(EditorScreen.number(v,"z2"));
            double elevation=EditorScreen.number(v,"elevation"),width=EditorScreen.number(v,"width");
            if(Math.hypot(x2-x1,z2-z1)<1 || width<1 || width>512)throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_runway"));
            String[] ends=name.split("/",-1);
            if(ends.length!=2 || Arrays.stream(ends).anyMatch(e->!e.matches("(0[1-9]|[12][0-9]|3[0-6])[LRC]?")) || ends[0].equals(ends[1]))
                throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_runway"));
            if(data().procedures.stream().anyMatch(p->Objects.equals(p.runwayId,r.id) &&
                    (!p.airportId.equals(airport.id) || !Arrays.asList(ends).contains(p.runwayEnd))))
                throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_procedure"));
            boolean enabled="on".equals(v.get("ils_enabled"));
            double slope=enabled?EditorScreen.number(v,"ils_slope"):r.ilsSlope;
            int f1=enabled?dev.airac.nav.RadioNavigation.parseFrequency(Kind.ILS,v.get("ils_frequency1")):r.ilsFrequency1;
            int f2=enabled?dev.airac.nav.RadioNavigation.parseFrequency(Kind.ILS,v.get("ils_frequency2")):r.ilsFrequency2;
            if(slope<=0 || slope>1_000_000 || enabled && f1==f2)throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_runway"));
            r.name=name;r.airportId=airport.id;r.x1=x1;r.z1=z1;r.x2=x2;r.z2=z2;r.elevation=elevation;r.width=width;
            r.ilsEnabled=enabled;r.ilsSlope=slope;r.ilsFrequency1=f1;r.ilsFrequency2=f2;
            if(fresh)data().runways.add(r);selectedRunway=r.id;runwayStart=null;app.changed();
        });
        if(client.currentScreen instanceof EditorScreen editor)editor.ilsOptions("ils_slope","ils_frequency1","ils_frequency2");
        if(airportParent!=null && client.currentScreen instanceof EditorScreen editor){Screen destination=airportParent;tool=Tool.AIRPORT;editor.returnTo(destination);}
    }
    private double sx(double x) { return (mapLeft+mapRight)/2.0+(x-centerX)*scale; }
    private double sy(double z) { return (mapTop+mapBottom)/2.0+(z-centerZ)*scale; }
    private NavMath.Vec world(double x,double y) { return new NavMath.Vec(centerX+(x-(mapLeft+mapRight)/2.0)/scale,centerZ+(y-(mapTop+mapBottom)/2.0)/scale); }
    private boolean inside(double x,double y) { return x>=mapLeft && x<mapRight && y>=mapTop && y<mapBottom; }
    private void mapLine(DrawContext c,double x1,double z1,double x2,double z2,int color) {
        Ui.clippedLine(c,sx(x1),sy(z1),sx(x2),sy(z2),mapLeft,mapTop,mapRight,mapBottom,color);
    }
    private Point hit(double x,double y) {
        Point best=null;double dist=100;
        boolean detail=80/scale<867;
        Set<String> connected=visibleRoutePoints();
        Point configuring=pointsEditor.preview();
        for(Point p:data().points) {
            if(!visibleOnMap(p,detail,connected,configuring))continue;
            Point displayed=configuring!=null && configuring.id.equals(p.id)?configuring:p;
            double d=Math.pow(sx(displayed.x)-x,2)+Math.pow(sy(displayed.z)-y,2);
            if(d<dist){dist=d;best=p;}
        }return best;
    }
    private boolean visibleOnMap(Point p,boolean detail,Set<String> connected,Point configuring){
        return detail || p.kind==Kind.AIRPORT || p.kind==Kind.VOR || p.kind==Kind.NDB || connected.contains(p.id)
                || configuring!=null && configuring.id.equals(p.id);
    }
    private Route displayedPlan(){
        Route route=data().activeRoute();
        return route==null && tool==Tool.PLAN?plan():route;
    }
    private Set<String> visibleRoutePoints(){
        Set<String> ids=data().mapConnectedPoints();
        Route route=displayedPlan();
        if(route!=null)ids.addAll(route.pointIds);
        if(tool==Tool.AIRWAY && airwayEditor.active())data().airways.stream().filter(a->a.id.equals(selectedAirway)).findFirst().ifPresent(a->ids.addAll(a.pointIds));
        return ids;
    }
    @Override public void render(DrawContext c,int mx,int my,float delta) {
        c.fill(0,0,width,height,Ui.BG);c.fill(0,0,width,21,0xff0a141d);
        Ui.text(c,"E F B",9,7,Ui.CYAN);Ui.fit(c,app.worldLabel+" / "+app.dimension,90,7,width-240,Ui.MUTED);
        Ui.fit(c,Ui.tr("ui.airac."+app.sync.statusKey()),width-142,7,134,Ui.CYAN);
        c.fill(5,73,side,height-25,Ui.PANEL);renderMap(c,mx,my);renderSidebar(c);
        String footer=!app.error.isEmpty()?app.error:!hint.isEmpty()?hint:Ui.tr("ui.airac.hint_"+tool.name().toLowerCase(Locale.ROOT));
        if(tool==Tool.PLAN && fpln)footer=!app.error.isEmpty()?"NAV DATA ERROR - CHECK CHART":!hint.isEmpty()?hint:"INIT / AIRWAYS / CLR     CLR: WPT / RIGHT CLICK: AIRWAY";
        if(tool==Tool.PLAN && !hint.isEmpty() && app.error.isEmpty())footer=hint;
        Ui.fit(c,footer,8,height-17,width-16,!app.error.isEmpty()?Ui.AMBER:Ui.MUTED);super.render(c,mx,my,delta);
        int bw=(width-22)/TOOLBAR.length;int selectedTool=Arrays.asList(TOOLBAR).indexOf(tool==Tool.RUNWAY?Tool.AIRPORT:tool);
        c.fill(8+selectedTool*(bw+1),43,8+selectedTool*(bw+1)+bw,45,Ui.CYAN);
    }
    private void renderMap(DrawContext c,int mx,int my) {
        c.enableScissor(mapLeft,mapTop,mapRight,mapBottom);
        if(app.settings.terrain)app.terrain.draw(c,mapLeft,mapTop,mapRight-mapLeft,mapBottom-mapTop,centerX,centerZ,scale);
        double spacing=Math.pow(2,Math.ceil(Math.log(90/scale)/Math.log(2)));
        var start=world(mapLeft,mapTop);var end=world(mapRight,mapBottom);
        for(double x=Math.floor(start.x()/spacing)*spacing;x<end.x();x+=spacing) {
            int screen=(int)sx(x);c.fill(screen,mapTop,screen+1,mapBottom,Ui.GRID);Ui.text(c,Integer.toString((int)x),screen+3,mapTop+4,Ui.MUTED);
        }
        for(double z=Math.floor(start.y()/spacing)*spacing;z<end.y();z+=spacing) {
            int screen=(int)sy(z);c.fill(mapLeft,screen,mapRight,screen+1,Ui.GRID);Ui.text(c,Integer.toString((int)z),mapLeft+3,screen+3,Ui.MUTED);
        }
        boolean detail=80/scale<867;
        Set<String> connected=visibleRoutePoints();
        Point configuring=pointsEditor.preview();
        if(detail || configuring!=null){
            Point transmitter=configuring;
            if(transmitter==null){
                Point selected=data().point(selectedPoint);
                if(selected!=null && dev.airac.nav.RadioNavigation.isStation(selected.kind))transmitter=selected;
            }
            if(transmitter!=null)renderStationCoverage(c,transmitter);
        }
        for(Airway a:data().airways) for(int i=1;i<a.pointIds.size();i++) {
            if(a.disconnectedBefore(i))continue;
            Point from=data().point(a.pointIds.get(i-1)),to=data().point(a.pointIds.get(i));if(from==null || to==null)continue;
            mapLine(c,from.x,from.z,to.x,to.z,a.oneWay?0xff398bff:a.id.equals(selectedAirway)?Ui.AMBER:0xff547c9b);
            double x=sx((from.x+to.x)/2),y=sy((from.z+to.z)/2);
            if(inside(x,y)){
                if(detail)Ui.lineLabel(c,a.name,sx(from.x),sy(from.z),sx(to.x),sy(to.z),a.oneWay?0xff398bff:0xffa1c4dc);
                if(a.oneWay)Ui.directionArrow(c,sx(from.x),sy(from.z),sx(to.x),sy(to.z),0xff398bff);
            }
        }
        if(detail)for(Procedure procedure:data().procedures) {
            Point previous=data().point(procedure.airportId);
            for(String fix:procedure.pointIds.reversed()) {
                Point next=data().point(fix);
                if(previous!=null && next!=null){
                    mapLine(c,previous.x,previous.z,next.x,next.z,0xff549b78);
                    if(inside(sx((previous.x+next.x)/2),sy((previous.z+next.z)/2)))
                        Ui.lineLabel(c,procedure.name,sx(previous.x),sy(previous.z),sx(next.x),sy(next.z),0xff70bc90);
                }
                previous=next;
            }
        }
        if(detail)for(Runway r:data().runways) {
            double dx=r.x2-r.x1,dz=r.z2-r.z1,len=r.length(),ox=-dz/len*r.width/2,oz=dx/len*r.width/2;
            int color=r.id.equals(selectedRunway)?Ui.AMBER:Ui.TEXT;
            mapLine(c,r.x1,r.z1,r.x2,r.z2,color);
            mapLine(c,r.x1+ox,r.z1+oz,r.x2+ox,r.z2+oz,color);mapLine(c,r.x1-ox,r.z1-oz,r.x2-ox,r.z2-oz,color);
            mapLine(c,r.x1+ox,r.z1+oz,r.x1-ox,r.z1-oz,color);mapLine(c,r.x2+ox,r.z2+oz,r.x2-ox,r.z2-oz,color);
            List<String> ends=r.ends();
            if(inside(sx(r.x1),sy(r.z1)))Ui.text(c,ends.getFirst(),(int)sx(r.x1)+6,(int)sy(r.z1)+8,Ui.TEXT);
            if(ends.size()>1 && inside(sx(r.x2),sy(r.z2)))Ui.text(c,ends.getLast(),(int)sx(r.x2)+6,(int)sy(r.z2)-12,Ui.TEXT);
        }
        for(Point ils:dev.airac.nav.RunwayIls.stations(data()))if(detail || ils.id.startsWith("ILS:"+selectedRunway+":")) {
            if(ils.id.startsWith("ILS:"+selectedRunway+":"))renderStationCoverage(c,ils);
            if(inside(sx(ils.x),sy(ils.z)))Ui.scaledFit(c,ils.ident+" "+dev.airac.nav.RadioNavigation.frequency(Kind.ILS,ils.frequency),
                    sx(ils.x)+6,sy(ils.z)-20,140,.7f,Ui.CYAN);
        }
        Route p=displayedPlan();
        if(p!=null)for(int i=1;i<p.pointIds.size();i++){
            Point a=data().point(p.pointIds.get(i-1)),b=data().point(p.pointIds.get(i));
            if(a!=null && b!=null && !p.disconnectedBefore(i))mapLine(c,a.x,a.z,b.x,b.z,Ui.MAGENTA);
        }
        if(tool==Tool.PLAN && fpln) {
            PerformanceProfile profile=performancePreview();
            if(profile!=null)for(var marker:profile.markers)if(inside(sx(marker.x()),sy(marker.z()))) {
                Ui.circle(c,sx(marker.x()),sy(marker.z()),4,Ui.CYAN);
                Ui.text(c,marker.label(),(int)sx(marker.x())+7,(int)sy(marker.z())-10,Ui.CYAN);
            }
        }
        boolean overview=80/scale>=867;
        for(Point point:data().points) {
            if(!visibleOnMap(point,detail,connected,configuring))continue;
            Point displayed=configuring!=null && configuring.id.equals(point.id)?configuring:point;
            boolean editing=displayed==configuring;
            double x=sx(displayed.x),y=sy(displayed.z);if(!inside(x,y))continue;
            boolean inPlan=p!=null && p.pointIds.contains(point.id);
            int color=point.id.equals(selectedPoint)?Ui.AMBER:inPlan?Ui.MAGENTA:point.kind==Kind.AIRPORT?Ui.CYAN:Ui.TEXT;
            if(point.kind==Kind.VOR)color=Ui.VOR_BLUE;
            if(point.kind==Kind.NDB)color=Ui.NDB_RED;
            if(point.kind==Kind.AIRPORT){Ui.circle(c,x,y,5,color);Ui.line(c,x-7,y,x+7,y,color);Ui.line(c,x,y-7,x,y+7,color);}
            else if(dev.airac.nav.RadioNavigation.isStation(point.kind))Ui.station(c,x,y,displayed,color);
            else Ui.diamond(c,x,y,4,color);
            boolean ring=point.kind==Kind.VOR && (detail || editing);
            if(ring)Ui.vorNorthRing(c,x,y,displayed.declination,Ui.VOR_BLUE);
            int labelX=(int)x+(ring?28:8);
            if(detail || editing || point.kind==Kind.AIRPORT && !inPlan)Ui.text(c,displayed.ident,labelX,(int)y-4,color);
            if((detail || editing) && dev.airac.nav.RadioNavigation.isStation(point.kind))
                Ui.scaledFit(c,dev.airac.nav.RadioNavigation.frequency(point.kind,displayed.frequency)+" "+dev.airac.nav.RadioNavigation.unit(point.kind),
                        labelX,y+6,90,0.75f,Ui.MUTED);
            if(overview && point.kind==Kind.AIRPORT && !inPlan && !point.name.isBlank() && !point.name.equals(point.ident))
                Ui.text(c,point.name,(int)x+8,(int)y+7,color);
            if(detail && inPlan)Ui.text(c,Integer.toString(p.pointIds.indexOf(point.id)+1),(int)x-8,(int)y-15,Ui.MAGENTA);
        }
        if(p!=null)for(String id:p.pointIds)if(id.startsWith("RWY:") && (detail || connected.contains(id))){
            Point threshold=data().point(id);if(threshold!=null && inside(sx(threshold.x),sy(threshold.z))){
                Ui.diamond(c,sx(threshold.x),sy(threshold.z),4,Ui.MAGENTA);
                if(detail)Ui.text(c,threshold.ident,(int)sx(threshold.x)+8,(int)sy(threshold.z)-4,Ui.MAGENTA);
            }
        }
        List<String> preview=detail?sidebar.previewIds():List.of();
        for(int i=1;i<preview.size();i++){
            Point from=data().point(preview.get(i-1)),to=data().point(preview.get(i));
            if(from!=null && to!=null){
                mapLine(c,from.x,from.z,to.x,to.z,Ui.CYAN);
                Ui.directionArrow(c,sx(from.x),sy(from.z),sx(to.x),sy(to.z),Ui.CYAN);
            }
        }
        for(String id:preview){
            Point fix=data().point(id);if(fix!=null && inside(sx(fix.x),sy(fix.z))){
                Ui.diamond(c,sx(fix.x),sy(fix.z),5,Ui.CYAN);
                Ui.text(c,fix.ident,(int)sx(fix.x)+8,(int)sy(fix.z)-4,Ui.CYAN);
            }
        }
        if(detail && runwayStart!=null && inside(mx,my))Ui.clippedLine(c,sx(runwayStart.x()),sy(runwayStart.y()),mx,my,mapLeft,mapTop,mapRight,mapBottom,Ui.AMBER);
        if(client.player!=null) {
            double x=sx(client.player.getX()),y=sy(client.player.getZ());
            if(inside(x,y))Ui.mapAircraft(c,x,y,NavMath.heading(client.player.getYaw()),Ui.GREEN);
        }
        c.disableScissor();Ui.text(c,"N",mapRight-17,mapTop+12,Ui.CYAN);
        Ui.line(c,mapRight-14,mapTop+26,mapRight-14,mapTop+40,Ui.CYAN);Ui.line(c,mapRight-14,mapTop+26,mapRight-18,mapTop+31,Ui.CYAN);
        c.fill(mapLeft,mapBottom-22,mapRight,mapBottom,0xf0101923);
        c.fill(mapLeft+7,mapBottom-16,mapLeft+87,mapBottom-15,Ui.TEXT);
        Ui.text(c,String.format(Locale.ROOT,"%.0f b",80/scale),mapLeft+7,mapBottom-12,Ui.TEXT);
        if(inside(mx,my)) {
            var w=world(mx,my);String coords=String.format(Locale.ROOT,"X %.0f  Z %.0f",Math.floor(w.x()),Math.floor(w.y()));
            Ui.text(c,coords,mapRight-textRenderer.getWidth(coords)-5,mapBottom-12,Ui.MUTED);
        }
    }
    private void renderStationCoverage(DrawContext c,Point p){
        int coverage=p.kind==Kind.VOR?Ui.VOR_BLUE:p.kind==Kind.NDB?Ui.NDB_RED:0xff386a72;
        for(int a=0;a<360;a+=4){
            double from=Math.toRadians(a),to=Math.toRadians(a+4);
            mapLine(c,p.x+Math.sin(from)*p.radius,p.z-Math.cos(from)*p.radius,
                    p.x+Math.sin(to)*p.radius,p.z-Math.cos(to)*p.radius,coverage);
        }
        if(inside(sx(p.x),sy(p.z)))Ui.scaledFit(c,String.format(Locale.ROOT,"R %.0f b",p.radius),
                sx(p.x)+8,sy(p.z)+17,120,0.75f,Ui.CYAN);
        if(p.kind==Kind.VOR){
            double angle=Math.toRadians(p.declination);
            mapLine(c,p.x,p.z,p.x+Math.sin(angle)*p.radius,p.z-Math.cos(angle)*p.radius,Ui.VOR_BLUE);
            double len=Math.min(p.radius,70/scale);
            mapLine(c,p.x,p.z,p.x,p.z-len,Ui.MUTED);
            if(inside(sx(p.x),sy(p.z)))Ui.scaledFit(c,String.format(Locale.ROOT,"VAR %+.1f deg",p.declination),
                    sx(p.x)+28,sy(p.z)+28,140,0.75f,Ui.VOR_BLUE);
        }
        if(p.kind==Kind.ILS){
            double h=Math.toRadians(p.course),dx=Math.sin(h),dz=-Math.cos(h);
            mapLine(c,p.x-dx*p.radius,p.z-dz*p.radius,p.x+dx*p.radius*.15,p.z+dz*p.radius*.15,Ui.CYAN);
            double len=Math.max(32/scale,Math.min(p.radius,100/scale));
            Ui.directionArrow(c,sx(p.x-dx*len),sy(p.z-dz*len),sx(p.x),sy(p.z),Ui.CYAN);
            if(inside(sx(p.x),sy(p.z)))Ui.scaledFit(c,String.format(Locale.ROOT,"CRS %03.0f / GS %.1f",p.course,p.glideSlope),
                    sx(p.x)+8,sy(p.z)+28,150,0.75f,Ui.CYAN);
        }
    }
    boolean stationTool(){return tool==Tool.SELECT || tool==Tool.WAYPOINT;}
    void openPoint(String id){
        sidebar.reset();selectedPoint=id;tool=Tool.WAYPOINT;scroll=0;refresh();
    }
    private void addStation(Kind kind){
        if(!app.canEditShared() || client.player==null)return;
        Point p=dev.airac.nav.RadioNavigation.createStation(data(),kind,client.player.getX(),client.player.getY(),client.player.getZ());
        data().points.add(p);selectedPoint=p.id;centerX=p.x;centerZ=p.z;app.changed();pointsEditor.open(p);
    }
    boolean isPlan(){return tool==Tool.PLAN;}
    boolean isAirport(){return tool==Tool.AIRPORT;}
    boolean isRunway(){return tool==Tool.RUNWAY;}
    Point airport(){Point p=data().point(selectedPoint);return p!=null && p.kind==Kind.AIRPORT?p:null;}
    void openAirport(String id){sidebar.reset();selectedPoint=id;selectedRunway=selectedProcedure=null;tool=Tool.AIRPORT;scroll=0;refresh();}
    void stopRunwayPick(){tool=Tool.AIRPORT;runwayStart=null;hint="";}
    void backToAirports(){selectedPoint=selectedRunway=selectedProcedure=null;runwayStart=null;tool=Tool.AIRPORT;refresh();}
    void locateRunway(Runway r){centerX=(r.x1+r.x2)/2;centerZ=(r.z1+r.z2)/2;refresh();}
    void sideButton(int y,Text label,Runnable action,boolean enabled){
        addDrawableChild(new FlatButton(8,y,side-16,18,label,b->action.run())).active=enabled;
    }
    void sideHalfButton(int y,boolean right,Text label,Runnable action,boolean enabled){
        int half=(side-19)/2;
        addDrawableChild(new FlatButton(right?11+half:8,y,half,18,label,b->action.run())).active=enabled;
    }
    void sideSmallButton(int y,Text label,Runnable action){addDrawableChild(new FlatButton(side-34,y,26,18,label,b->action.run()));}
    void sideField(net.minecraft.client.gui.widget.TextFieldWidget field){addDrawableChild(field);}
    private PerformanceProfile performancePreview() {
        if(!FlightAssistantIntegration.available())return null;
        Route route=plan();
        if(route!=previewRoute || app.chartEpoch!=previewEpoch || app.planRevision!=previewRevision) {
            previewRoute=route;previewEpoch=app.chartEpoch;previewRevision=app.planRevision;
            previewProfile=PerformanceProfile.preview(data(),route,client!=null && client.player!=null?client.player.getY():0);
        }
        return previewProfile;
    }
    private List<RouteRows.Row> planRows() {return RouteRows.of(plan(),fpln?performancePreview():null);}
    private List<String> rowLabels() {
        List<String> labels=new ArrayList<>();
        if(tool==Tool.PLAN) {
            Route p=plan();
            for(var row:planRows()){
                if(row.kind()==RouteRows.Kind.TC || row.kind()==RouteRows.Kind.TD){labels.add("     ("+(row.kind()==RouteRows.Kind.TC?"T.C":"T.D")+")");continue;}
                if(row.kind()==RouteRows.Kind.GAP){labels.add(Ui.tr("ui.airac.plan_disconnect"));continue;}
                if(row.kind()==RouteRows.Kind.END){labels.add("+ "+(fpln?"END OF F-PLN":Ui.tr("ui.airac.append_end")));continue;}
                int i=row.index();Point point=data().point(p.pointIds.get(i));
                labels.add(String.format(Locale.ROOT,"%02d %s  %s",i+1,i==0 && p.departureRunwayId!=null?"DEP":p.vias.get(i),point==null?"?":point.ident));
            }
        } else for(Entry e:sidebarEntries())labels.add(entryLabel(e));
        return labels;
    }
    private record Entry(Point point,Airway airway,Runway runway) {
        static Entry point(Point p){return new Entry(p,null,null);}
        static Entry airway(Airway a){return new Entry(null,a,null);}
        static Entry runway(Runway r){return new Entry(null,null,r);}
    }
    private boolean querying(){return tool!=Tool.PLAN && tool!=Tool.RADIO && searchQuery!=null && !searchQuery.isBlank();}
    private boolean matches(String... values){
        if(!querying())return true;
        String q=searchQuery.trim().toLowerCase(Locale.ROOT);
        for(String value:values)if(value!=null && value.toLowerCase(Locale.ROOT).contains(q))return true;
        return false;
    }
    private boolean navaid(Kind kind){return kind==Kind.WAYPOINT || kind==Kind.VOR || kind==Kind.NDB;}
    /** Select searches every later tab: waypoints, airports, and chart airways. */
    private List<Entry> sidebarEntries(){
        List<Entry> rows=new ArrayList<>();
        if(tool==Tool.PLAN || tool==Tool.RADIO)return rows;
        if(tool==Tool.RUNWAY){for(Runway r:visibleRunways())if(matches(r.name))rows.add(Entry.runway(r));return rows;}
        if(tool!=Tool.AIRWAY)for(Point p:data().points)if(pointListed(p))rows.add(Entry.point(p));
        if(tool==Tool.AIRWAY || tool==Tool.SELECT && querying())for(Airway a:data().airways){
            Point from=a.pointIds.isEmpty()?null:data().point(a.pointIds.getFirst());
            Point to=a.pointIds.size()<2?null:data().point(a.pointIds.getLast());
            if(matches(a.name,from==null?null:from.ident,to==null?null:to.ident))rows.add(Entry.airway(a));
        }
        return rows;
    }
    private boolean pointListed(Point p){
        if(tool==Tool.AIRPORT && p.kind!=Kind.AIRPORT)return false;
        if(!querying())return true;
        if(tool==Tool.WAYPOINT && !navaid(p.kind))return false;
        if(tool==Tool.SELECT && !(navaid(p.kind) || p.kind==Kind.AIRPORT))return false;
        return matches(p.ident,p.name);
    }
    private String entryLabel(Entry e){
        if(e.point!=null)return (e.point.kind==Kind.AIRPORT?"> ":"+ ")+e.point.ident;
        if(e.runway!=null)return "  RWY "+e.runway.name;
        Point from=e.airway.pointIds.isEmpty()?null:data().point(e.airway.pointIds.getFirst());
        Point to=e.airway.pointIds.size()<2?null:data().point(e.airway.pointIds.getLast());
        return e.airway.name+(e.airway.oneWay?" > ":"  ")+(from==null?"?":from.ident)+" - "+(to==null?"?":to.ident);
    }
    private void renderSidebar(DrawContext c) {
        if(tool==Tool.RADIO){radios.render(c);return;}
        Route p=plan();
        if(tool==Tool.PLAN){
            Ui.fit(c,p==null?Ui.tr("ui.airac.no_plan"):p.name,39,86,side-77,Ui.MAGENTA);
        }else{
            Ui.fit(c,Ui.tr("ui.airac.tool_"+tool.name().toLowerCase(Locale.ROOT)),10,83,side-20,Ui.CYAN);
            Point selected=data().point(selectedPoint);Ui.fit(c,selected==null?Ui.tr("ui.airac.select_point"):selected.ident,10,99,side-20,Ui.MUTED);
        }
        if(pointsEditor.active()){pointsEditor.render(c);return;}
        if(airwayEditor.active()){airwayEditor.render(c);return;}
        if(sidebar.customRows()){sidebar.render(c);return;}
        var labels=rowLabels();var routeRows=planRows();var entries=tool==Tool.PLAN?List.<Entry>of():sidebarEntries();
        int visible=Math.max(0,(listBottom-listTop)/16);
        scroll=Math.clamp(scroll,0,Math.max(0,labels.size()-visible));
        c.enableScissor(7,listTop,side-3,Math.max(listTop,listBottom));
        for(int i=scroll;i<Math.min(labels.size(),scroll+visible);i++){
            int y=listTop+(i-scroll)*16;
            if(tool==Tool.PLAN && routeRows.get(i).kind()==RouteRows.Kind.GAP){
                RouteRows.controls(c,8,y,side-6,p.editAfter==routeRows.get(i).index()-1);continue;
            }
            Entry e=tool==Tool.PLAN?null:entries.get(i);
            boolean selected;
            if(tool==Tool.PLAN)selected=routeRows.get(i).kind()==RouteRows.Kind.FIX && routeRows.get(i).index()==selectedLeg;
            else selected=e.point!=null && e.point.id.equals(selectedPoint) || e.airway!=null && e.airway.id.equals(selectedAirway) || e.runway!=null && e.runway.id.equals(selectedRunway);
            if(selected)c.fill(7,y-2,side-3,y+13,0xff304954);
            int color=tool==Tool.PLAN?Ui.MAGENTA:e.airway!=null && e.airway.oneWay?0xff398bff:Ui.TEXT;
            Ui.fit(c,labels.get(i),11,y+2,side-25,color);
        }
        c.disableScissor();
    }
    private void listClick(double mouseX,double mouseY) {
        if(airwayEditor.click(mouseX,mouseY))return;
        int index=(int)(mouseY-listTop)/16+scroll;if(index<0 || index>=rowLabels().size())return;
        if(tool==Tool.PLAN){
            Route r=plan();var row=planRows().get(index);
            if(row.kind()==RouteRows.Kind.TC || row.kind()==RouteRows.Kind.TD)return;
            if(row.kind()==RouteRows.Kind.END){if(app.canEditPlans())RoutePlanner.appendAtEnd(r);return;}
            if(row.kind()==RouteRows.Kind.GAP){
                if(!app.canEditPlans())return;
                if(mouseX<26)RoutePlanner.beginGap(r,row.index());
                else if(mouseX>side-24){RoutePlanner.connectGap(data(),r,row.index());app.planChanged();}
                return;
            }
            selectedLeg=row.index();Point p=data().point(r.pointIds.get(selectedLeg));
            if(p!=null){selectedPoint=p.id;centerX=p.x;centerZ=p.z;}
        }else{
            Entry e=sidebarEntries().get(index);
            if(e.airway!=null){
                selectedPoint=null;selectedAirway=e.airway.id;
                if(e.airway.pointIds.size()>=2){
                    Point from=data().point(e.airway.pointIds.getFirst()),to=data().point(e.airway.pointIds.getLast());
                    if(from!=null && to!=null){centerX=(from.x+to.x)/2;centerZ=(from.z+to.z)/2;}
                }
                if(tool==Tool.AIRWAY){airwayFrom=buildingAirway=null;airwayEditor.open(e.airway,false);refresh();}
            }else if(e.runway!=null){
                selectedRunway=e.runway.id;centerX=(e.runway.x1+e.runway.x2)/2;centerZ=(e.runway.z1+e.runway.z2)/2;
            }else{
                Point p=e.point;selectedAirway=null;selectedPoint=p.id;centerX=p.x;centerZ=p.z;
                if(stationTool() && dev.airac.nav.RadioNavigation.isStation(p.kind))pointsEditor.open(p);
                else if(tool==Tool.AIRPORT)openAirport(p.id);
            }
        }
    }
    private void mapClick(double mx,double my,int button) {
        Point point=hit(mx,my);var w=world(mx,my);
        if(tool==Tool.RADIO)return;
        if(stationTool() && point!=null && dev.airac.nav.RadioNavigation.isStation(point.kind)){pointsEditor.open(point);return;}
        if(sidebar.mapPick(point,button))return;
        if(airwayEditor.mapPick(point,button))return;
        if(tool==Tool.PLAN && fpln){if(point!=null)selectedPoint=point.id;return;}
        if(button==1){if(point!=null)editPoint(point,false);return;}
        if(tool==Tool.AIRPORT && point!=null && point.kind==Kind.AIRPORT){selectedPoint=point.id;openAirport(point.id);return;}
        if(tool==Tool.PLAN?!app.canEditPlans():tool!=Tool.SELECT && !app.canEditShared())return;
        switch(tool) {
            case SELECT -> {selectedPoint=point==null?null:point.id;pointsEditor.reset();refresh();}
            case WAYPOINT -> {
                pointsEditor.reset();
                if(point!=null)selectedPoint=point.id;
                else {
                    Point p=new Point(nextIdent("FIX"),Kind.WAYPOINT,ChartData.blockCenter(w.x()),0,ChartData.blockCenter(w.y()));
                    data().points.add(p);selectedPoint=p.id;app.changed();
                    pointsEditor.open(p);
                }
                refresh();
            }
            case AIRPORT -> {
                if(point!=null){selectedPoint=point.id;editPoint(point,false);}
                else editPoint(new Point(nextIdent(tool==Tool.AIRPORT?"APT":"FIX"),tool==Tool.AIRPORT?Kind.AIRPORT:Kind.WAYPOINT,
                        ChartData.blockCenter(w.x()),client.player==null?64:Math.round(client.player.getY()),ChartData.blockCenter(w.y())),true);
            }
            case RADIO -> {}
            case PLAN -> {if(point!=null){selectedPoint=point.id;appendPlan(point);}}
            case AIRWAY -> {
                if(point==null)return;selectedPoint=point.id;
                if(airwayFrom==null){airwayFrom=point.id;hint=Ui.tr("ui.airac.pick_second");}
                else if(!airwayFrom.equals(point.id)){
                    Airway current=data().airways.stream().filter(a->a.id.equals(buildingAirway)).findFirst().orElse(null);
                    if(current!=null) {
                        if(current.pointIds.size()>=2048){hint=Ui.tr("ui.airac.sync_invalid");return;}
                        dev.airac.nav.AirwayEditor.insert(data(),current,-1,point.id);airwayFrom=point.id;hint=Ui.tr("ui.airac.continue_airway");changed();
                    } else {Airway a=new Airway();a.name="A"+(data().airways.size()+1);a.pointIds.addAll(List.of(airwayFrom,point.id));editAirway(a,true);}
                }
            }
            case RUNWAY -> {
                if(point!=null && point.kind==Kind.AIRPORT){selectedPoint=point.id;runwayStart=null;hint=Ui.tr("ui.airac.pick_threshold");return;}
                Point airport=data().point(selectedPoint);
                if(airport==null || airport.kind!=Kind.AIRPORT){hint=Ui.tr("ui.airac.pick_airport");return;}
                if(runwayStart==null){runwayStart=w;hint=Ui.tr("ui.airac.pick_end");}
                else {
                    Runway r=new Runway();r.airportId=airport.id;r.x1=ChartData.blockCenter(runwayStart.x());r.z1=ChartData.blockCenter(runwayStart.y());
                    r.x2=ChartData.blockCenter(w.x());r.z2=ChartData.blockCenter(w.y());r.elevation=airport.y;
                    int h=(int)Math.round(NavMath.bearing(r.x2-r.x1,r.z2-r.z1)/10);if(h==0)h=36;
                    r.name=String.format(Locale.ROOT,"%02d/%02d",h,(h+17)%36+1);editRunway(r,true);hint="";
                }
            }
        }
    }
    @Override public boolean mouseClicked(Click click,boolean doubled) {
        if(click.button()==1 && sidebar.rightClear(click.x(),click.y()))return true;
        if(super.mouseClicked(click,doubled))return true;
        if(doubled && click.button()==0 && stationTool() && inside(click.x(),click.y())){
            Point p=hit(click.x(),click.y());
            if(p!=null && p.kind==Kind.WAYPOINT){mapHeld=false;pointsEditor.open(p);return true;}
        }
        if(click.x()<side && click.y()>=listTop && click.y()<listBottom){
            if(click.button()==0 && tool!=Tool.RADIO && !pointsEditor.active() && !sidebar.click(click.x(),click.y())){
                listClick(click.x(),click.y());Point p=data().point(selectedPoint);
                if(doubled && stationTool() && p!=null && p.kind==Kind.WAYPOINT)pointsEditor.open(p);
            }
            return true;
        }
        if(inside(click.x(),click.y()) && (click.button()==0 || click.button()==1)) {
            mapHeld=true;dragged=false;clickX=click.x();clickY=click.y();return true;
        }return false;
    }
    @Override public boolean mouseDragged(Click click,double dx,double dy) {
        if(mapHeld) {
            if(Math.hypot(click.x()-clickX,click.y()-clickY)>3)dragged=true;
            if(dragged){centerX=Math.clamp(centerX-dx/scale,-30_000_000,30_000_000);centerZ=Math.clamp(centerZ-dy/scale,-30_000_000,30_000_000);}return true;
        }return super.mouseDragged(click,dx,dy);
    }
    @Override public boolean mouseReleased(Click click) {
        if(mapHeld){mapHeld=false;if(!dragged && inside(click.x(),click.y()))mapClick(click.x(),click.y(),click.button());return true;}
        return super.mouseReleased(click);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical) {
        if(mx<side && my>=listTop && my<listBottom){
            if(tool==Tool.RADIO)return radios.scroll(vertical);
            if(pointsEditor.scroll(vertical))return true;
            if(sidebar.scroll(vertical))return true;scroll=Math.max(0,scroll-(int)Math.signum(vertical)*3);return true;}
        if(inside(mx,my)) {
            var before=world(mx,my);scale=Math.clamp(scale*Math.pow(1.25,vertical),0.004,8);
            var after=world(mx,my);centerX+=before.x()-after.x();centerZ+=before.y()-after.y();return true;
        }return super.mouseScrolled(mx,my,horizontal,vertical);
    }
    @Override public boolean keyPressed(KeyInput input) {
        if(getFocused() instanceof net.minecraft.client.gui.widget.TextFieldWidget)return super.keyPressed(input);
        if(app.openKey.matchesKey(input)){close();return true;}
        if(input.key()==GLFW.GLFW_KEY_F){centerPlayer();return true;}return super.keyPressed(input);
    }
    @Override public boolean shouldPause(){return false;}
    @Override public void close(){app.save();super.close();}
}
