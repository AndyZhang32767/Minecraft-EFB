package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.ChartData.*;
import dev.airac.nav.RoutePlanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;

/** Map-preserving airport and F-PLN menus. Pending edits never touch a plan. */
final class ChartSidebar {
    private final ChartScreen host;
    private final AiracClient app=AiracClient.INSTANCE;
    private String page="";
    private final Map<String,String> values=new LinkedHashMap<>();
    private final List<String> labels=new ArrayList<>();
    private final List<Runnable> actions=new ArrayList<>();
    private final Map<String,Integer> fieldLabels=new LinkedHashMap<>();
    private int top,bottom,scroll,formScroll;
    private boolean departure,pending;
    private String runwayId,runwayEnd="",procedureId;
    private Procedure draft;
    private boolean fresh;
    private long epoch;
    private String error="";
    private record End(String id,String end) {}
    private List<End> ends=new ArrayList<>();
    private int endIndex;
    ChartSidebar(ChartScreen host){this.host=host;}
    boolean active(){return host.fpln && host.isPlan() || host.isAirport() && host.airport()!=null || host.isRunway();}
    String heading(){return switch(page){case "init"->"INIT";case "endpoints"->"DEP/ARR";case "runways","procedures"->departure?"DEP RWY/SID":"ARR RWY/STAR";case "airways"->"AIRWAYS";default->"";};}
    boolean english(){return host.isPlan() && host.fpln;}
    void reset(){page="";values.clear();draft=null;pending=false;scroll=formScroll=0;error="";}
    void updated(){if(!page.isEmpty()){reset();error=english()?"NAV DATA CHANGED - RESELECT":Ui.tr("ui.airac.sync_conflict");}}
    private void open(String next){page=next;values.clear();pending=false;scroll=formScroll=0;error="";epoch=app.chartEpoch;host.refresh();}
    private void button(int y,String label,Runnable run,boolean enabled){
        host.sideButton(y,Text.literal(label),run,enabled);
    }
    private void local(int y,String key,Runnable run,boolean enabled){host.sideButton(y,Ui.t(key),run,enabled);}
    private void row(String label,Runnable action){labels.add(label);actions.add(action);}
    private void field(String key,String label,int row,String initial){
        int y=top+row*30-formScroll;
        fieldLabels.put(label,y);
        values.putIfAbsent(key,initial);
        if(y<top || y+28>bottom)return;
        var widget=new TextFieldWidget(MinecraftClient.getInstance().textRenderer,10,y+10,host.side-20,17,
                english()?McduText.t(key):Ui.t(key));
        widget.setMaxLength(key.equals("star_fixes")?8192:80);
        widget.setText(values.get(key));
        widget.setChangedListener(value->values.put(key,value));
        widget.setEditable(english()?app.canEditPlans():app.canEditShared());
        host.sideField(widget);
    }
    private void check(boolean shared){
        if(epoch!=app.chartEpoch)throw new IllegalArgumentException("sync_conflict");
        if(shared?!app.canEditShared():!app.canEditPlans())throw new IllegalArgumentException("sync_read_only");
    }
    private void attempt(boolean shared,Runnable action){
        try{check(shared);action.run();error="";}
        catch(IllegalArgumentException ex){error=english()?McduText.tr(ex.getMessage()):Ui.tr("ui.airac."+ex.getMessage());}
    }
    void init(){
        labels.clear();actions.clear();fieldLabels.clear();
        top=134;bottom=host.height-74;
        if(english()){
            Route r=host.plan();
            if(page.isEmpty()){
                host.listTop=134;host.listBottom=host.height-114;
                button(host.height-110,"INIT",()->open("init"),app.canEditPlans());
                host.sideHalfButton(host.height-90,false,Text.literal("AIRWAYS"),this::airways,app.canEditPlans() && r!=null);
                host.sideHalfButton(host.height-90,true,Text.literal("NEW"),host::newPlan,app.canEditPlans());
                if(dev.airac.compat.FlightAssistantIntegration.available()) {
                    host.sideHalfButton(host.height-50,false,Text.literal("PERF"),host::performance,true);
                    host.sideHalfButton(host.height-50,true,Text.literal("TMPY"),host::applyPlan,app.canEditPlans() && r!=null);
                }
                button(host.height-70,"CLR",()->{if(r!=null){host.selectedLeg=RoutePlanner.remove(app.chart,r,host.selectedLeg);app.planChanged();host.refresh();}},app.canEditPlans() && r!=null);
                return;
            }
            if(page.equals("init")){
                row("DEP/ARR",this::endpoints);
                row("DEP RWY/SID",()->terminal(true));
                row("ARR RWY/STAR",()->terminal(false));
            } else if(page.equals("endpoints")){
                field("departure","DEP",0,ident(r.departureId));
                field("arrival","ARR",1,ident(r.arrivalId));
                button(host.height-70,"INSERT*",()->attempt(false,()->{
                    Point dep=RoutePlanner.airportByIdent(app.chart,values.get("departure"));
                    Point arr=RoutePlanner.airportByIdent(app.chart,values.get("arrival"));
                    RoutePlanner.endpoints(app.chart,r,dep,arr);app.planChanged();open("init");
                }),app.canEditPlans());
            } else if(page.equals("runways")){
                row("NONE",()->selectRunway(null,""));
                String airport=departure?r.departureId:r.arrivalId;
                for(Runway rw:app.chart.runways)if(rw.airportId.equals(airport))
                    for(String end:rw.ends())row("RWY "+end,()->selectRunway(rw.id,end));
            } else if(page.equals("procedures")){
                row((pending && procedureId==null?"> ":"")+"NONE",()->preview(null));
                String airport=departure?r.departureId:r.arrivalId;
                for(Procedure p:app.chart.procedures)
                    if(RoutePlanner.compatible(app.chart,p,airport,runwayId,runwayEnd,departure))
                        row((Objects.equals(procedureId,p.id)?"> ":"")+(departure?"SID ":"STAR ")+p.name,()->preview(p.id));
                button(host.height-70,"INSERT*",()->attempt(false,()->{
                    RoutePlanner.terminal(app.chart,r,departure,runwayId,runwayEnd,procedureId);
                    app.planChanged();open("init");
                }),app.canEditPlans() && pending);
            } else if(page.equals("airways")){
                int index=RoutePlanner.editIndex(r);
                field("entry","FROM",0,index>0?ident(r.pointIds.get(index-1)):"");
                field("airway_name","VIA",1,"");
                field("exit","TO",2,"");
                button(host.height-70,"INSERT*",()->attempt(false,()->{
                    Point to=RoutePlanner.byIdent(app.chart,values.get("exit"));
                    if(to==null)throw new IllegalArgumentException("invalid_endpoints");
                    String via=values.get("airway_name").trim();
                    if(via.isEmpty() || via.equalsIgnoreCase("DIR"))
                        host.selectedLeg=RoutePlanner.direct(app.chart,r,r.editAfter,to.id);
                    else{
                        Point from=RoutePlanner.byIdent(app.chart,values.get("entry"));
                        if(from==null)throw new IllegalArgumentException("invalid_endpoints");
                        host.selectedLeg=RoutePlanner.airway(app.chart,r,r.editAfter,from.id,via,to.id);
                    }
                    app.planChanged();open("airways");
                }),app.canEditPlans());
            }
            button(host.height-50,"<RETURN",()->open(page.equals("init") || page.equals("airways")?"":"init"),true);
        } else {
            Point airport=host.airport();if(airport==null)return;
            boolean edit=app.canEditShared();
            if(page.equals("program")){
                field("name",Ui.tr("ui.airac.name")+" / STAR",0,fresh?"STAR"+(app.chart.procedures.size()+1):draft.name);
                field("star_fixes",Ui.tr("ui.airac.star_fixes"),1,String.join(" ",draft.pointIds.stream().map(this::ident).toList()));
                int y=top+60-formScroll;
                fieldLabels.put("ARR RWY",y);
                if(y>=top && y+28<=bottom){
                    End end=ends.get(endIndex);
                    button(y+10,end.id==null?Ui.tr("ui.airac.all_runways"):"RWY "+end.end,()->{
                        endIndex=(endIndex+1)%ends.size();host.refresh();
                    },edit);
                }
                local(host.height-70,"save",()->attempt(true,this::saveProgram),edit);
                local(host.height-50,"cancel",()->{reset();host.refresh();},true);
            } else {
                if(host.height>=320){
                local(108,"add_runway",()->{reset();host.startRunway(airport.id,host);},edit);
                local(128,"add_procedure",()->editProgram(new Procedure(),true),edit);
                local(148,"edit_airport",()->host.editAirport(airport.id,host),edit);
                top=172;
                }else{
                    top=108;
                    if(edit){
                        row(Ui.tr("ui.airac.add_runway"),()->{reset();host.startRunway(airport.id,host);});
                        row(Ui.tr("ui.airac.add_procedure"),()->editProgram(new Procedure(),true));
                        row(Ui.tr("ui.airac.edit_airport"),()->host.editAirport(airport.id,host));
                    }
                }
                if(host.isRunway())row(Ui.tr("ui.airac.pick_threshold"),()->{});
                for(Runway rw:app.chart.runways)if(rw.airportId.equals(airport.id))
                    row((Objects.equals(host.selectedRunway,rw.id)?"> ":"")+"RWY "+rw.name,()->{
                        host.selectedRunway=rw.id;host.selectedProcedure=null;host.locateRunway(rw);
                    });
                for(Procedure p:app.chart.procedures)if(p.airportId.equals(airport.id))
                    row((Objects.equals(host.selectedProcedure,p.id)?"> ":"")+"STAR "+p.name,()->{
                        host.selectedProcedure=p.id;host.selectedRunway=null;host.refresh();
                    });
                local(host.height-70,"edit",()->{
                    if(host.selectedProcedure!=null){Procedure p=app.chart.procedure(host.selectedProcedure);if(p!=null)editProgram(p,false);}
                    else host.editSelectedRunway();
                },edit && (host.selectedRunway!=null || host.selectedProcedure!=null));
                local(host.height-50,"delete",()->host.confirm("confirm_delete",()->{
                    if(host.selectedProcedure!=null)app.chart.removeProcedure(host.selectedProcedure);
                    else if(host.selectedRunway!=null)app.chart.removeRunway(host.selectedRunway);
                    host.selectedProcedure=host.selectedRunway=null;app.changed();host.refresh();
                }),edit && (host.selectedRunway!=null || host.selectedProcedure!=null));
                host.sideSmallButton(80,Ui.t("previous"),()->{reset();host.backToAirports();});
            }
        }
        host.listTop=top;host.listBottom=bottom;
    }
    private void endpoints(){open("endpoints");}
    private void terminal(boolean dep){
        Route r=host.plan();if(r==null)return;
        if(r.departureId==null || r.arrivalId==null){endpoints();return;}
        departure=dep;runwayId=null;runwayEnd="";procedureId=null;open("runways");
    }
    private void selectRunway(String id,String end){
        runwayId=id;runwayEnd=end;procedureId=null;open("procedures");
    }
    private void preview(String id){procedureId=id;pending=true;host.refresh();}
    private void airways(){
        Route r=host.plan();if(r==null)return;
        if(r.departureId==null || r.arrivalId==null){endpoints();return;}
        if(!r.departureConfigured){terminal(true);return;}
        if(!r.arrivalConfigured){terminal(false);return;}
        open("airways");
    }
    private void editProgram(Procedure p,boolean isNew){
        host.stopRunwayPick();reset();draft=p;fresh=isNew;epoch=app.chartEpoch;page="program";
        ends=new ArrayList<>();ends.add(new End(null,""));endIndex=0;
        for(Runway r:app.chart.runways)if(r.airportId.equals(host.airport().id))
            for(String end:r.ends()){
                ends.add(new End(r.id,end));
                if(Objects.equals(p.runwayId,r.id) && p.runwayEnd.equals(end))endIndex=ends.size()-1;
            }
        host.refresh();
    }
    private List<String> parseFixes(boolean strict){
        List<String> ids=new ArrayList<>();
        for(String token:values.getOrDefault("star_fixes","").trim().split("[\\s,]+")){
            if(token.isBlank())continue;
            Point p=RoutePlanner.byIdent(app.chart,token);
            if(p==null || p.id.equals(host.airport().id) || !ids.isEmpty() && ids.getLast().equals(p.id)){
                if(strict)throw new IllegalArgumentException("invalid_endpoints");
                continue;
            }
            ids.add(p.id);
        }
        if(strict && (ids.isEmpty() || ids.size()>2048))throw new IllegalArgumentException("invalid_procedure");
        return ids;
    }
    private void saveProgram(){
        Point airport=host.airport();
        String name=values.get("name").trim().toUpperCase(Locale.ROOT);
        if(name.isEmpty() || name.length()>80)throw new IllegalArgumentException("invalid_procedure");
        List<String> fixes=parseFixes(true);End end=ends.get(endIndex);
        if(app.chart.procedures.stream().anyMatch(p->p!=draft && p.airportId.equals(airport.id)
                && p.name.equalsIgnoreCase(name) && Objects.equals(p.runwayId,end.id) && p.runwayEnd.equals(end.end)))
            throw new IllegalArgumentException("duplicate");
        if(!fresh)app.chart.invalidateProcedureSelections(draft.id);
        draft.airportId=airport.id;draft.runwayId=end.id;draft.runwayEnd=end.end;draft.name=name;draft.pointIds=new ArrayList<>(fixes);
        if(fresh)app.chart.procedures.add(draft);
        host.selectedProcedure=draft.id;reset();app.changed();host.refresh();
    }
    boolean mapPick(Point p,int button){
        if(!page.equals("program"))return false;
        if(!app.canEditShared() || p==null)return true;
        if(button==1){
            List<String> ids=parseFixes(false);if(!ids.isEmpty())ids.removeLast();
            values.put("star_fixes",String.join(" ",ids.stream().map(this::ident).toList()));
        } else if(!p.id.equals(host.airport().id)){
            List<String> ids=parseFixes(false);
            if(ids.isEmpty() || !ids.getLast().equals(p.id))
                values.put("star_fixes",(values.getOrDefault("star_fixes","").trim()+" "+p.ident).trim());
        }
        host.refresh();return true;
    }
    List<String> previewIds(){
        if(page.equals("program") && host.airport()!=null){
            List<String> ids=parseFixes(false);ids.add(host.airport().id);return ids;
        }
        Procedure p=app.chart.procedure(english() && page.equals("procedures")?procedureId:
                !english()?host.selectedProcedure:null);
        if(p==null)return List.of();
        List<String> ids=new ArrayList<>(p.pointIds);
        String rw=english()?runwayId:p.runwayId;
        String end=english()?runwayEnd:p.runwayEnd;
        ids.add(rw==null?p.airportId:dev.airac.data.ChartData.thresholdId(rw,end));
        if(english() && departure)Collections.reverse(ids);
        return ids;
    }
    boolean customRows(){return active() && !(english() && page.isEmpty());}
    boolean click(double x,double y){
        if(!customRows())return false;
        int index=(int)(y-top)/16+scroll;
        if(index>=0 && index<actions.size())actions.get(index).run();
        return true;
    }
    boolean scroll(double amount){
        if(!customRows())return false;
        if(!fieldLabels.isEmpty()){
            int rows=page.equals("endpoints")?2:3;
            formScroll=Math.clamp(formScroll-(int)Math.signum(amount)*30,0,Math.max(0,rows*30-(bottom-top)));
            host.refresh();
        } else scroll=Math.max(0,scroll-(int)Math.signum(amount)*3);
        return true;
    }
    boolean rightClear(double x,double y){
        if(!english() || !page.isEmpty() || x<8 || x>=host.side-8 || y<host.height-70 || y>=host.height-52)return false;
        Route r=host.plan();if(r==null || !app.canEditPlans())return true;
        host.selectedLeg=RoutePlanner.removeAirway(app.chart,r,host.selectedLeg);
        app.planChanged();host.refresh();return true;
    }
    void render(DrawContext c){
        if(!customRows())return;
        String heading=english()?switch(page){
            case "init"->"INIT";case "endpoints"->"DEP/ARR";
            case "runways","procedures"->(departure?"DEP RWY/SID":"ARR RWY/STAR");
            default->"AIRWAYS";
        }:page.equals("program")?"ARR STAR : OUTER > INNER":"";
        if(!english() && !heading.isEmpty())Ui.fit(c,heading,10,124,host.side-20,Ui.CYAN);
        int visible=Math.max(0,(bottom-top)/16);
        scroll=Math.clamp(scroll,0,Math.max(0,labels.size()-visible));
        c.enableScissor(7,top,host.side-3,Math.max(top,bottom));
        for(int i=scroll;i<Math.min(labels.size(),scroll+visible);i++)
            Ui.fit(c,labels.get(i),10,top+(i-scroll)*16+2,host.side-20,Ui.TEXT);
        for(var entry:fieldLabels.entrySet())Ui.fit(c,entry.getKey(),10,entry.getValue(),host.side-20,Ui.MUTED);
        c.disableScissor();
        if(!error.isEmpty())Ui.scaledFit(c,error,10,host.height-84,host.side-20,0.75f,Ui.AMBER);
    }
    private String ident(String id){Point p=app.chart.point(id);return p==null?"":p.ident;}
}
