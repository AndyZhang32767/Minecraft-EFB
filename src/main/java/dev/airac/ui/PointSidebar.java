package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.ChartData;
import dev.airac.data.ChartData.*;
import dev.airac.nav.RadioNavigation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;

/** Point and transmitter parameters stay beside the map, including on compact displays. */
final class PointSidebar {
    private final ChartScreen host;
    private final AiracClient app=AiracClient.INSTANCE;
    private final Map<String,String> values=new LinkedHashMap<>();
    private final Map<String,Integer> labels=new LinkedHashMap<>();
    private int offset, rows, top, bottom;
    private String id, waypointId, error="";
    private long epoch;
    PointSidebar(ChartScreen host){this.host=host;}
    void reset(){id=waypointId=null;values.clear();offset=0;error="";}
    void updated(){
        String editing=waypointId;
        reset();
        Point p=app.chart.point(editing);
        if(host.stationTool() && p!=null && p.kind==Kind.WAYPOINT && p.id.equals(host.selectedPoint))waypointId=p.id;
    }
    private Point selected(){
        Point p=app.chart.point(host.selectedPoint);
        return host.stationTool() && p!=null && (RadioNavigation.isStation(p.kind)
                || p.kind==Kind.WAYPOINT && p.id.equals(waypointId))?p:null;
    }
    boolean active(){return selected()!=null;}
    void open(Point p){reset();if(p.kind==Kind.WAYPOINT)waypointId=p.id;host.openPoint(p.id);}
    private String number(double value){return String.format(Locale.ROOT,"%.4f",value).replaceAll("0+$","").replaceAll("\\.$","");}
    private void load(Point p){
        if(p.id.equals(id))return;
        values.clear();offset=0;error="";id=p.id;epoch=app.chartEpoch;
        values.put("ident",p.ident);values.put("x",Long.toString((long)Math.floor(p.x)));
        values.put("y",number(p.y));values.put("z",Long.toString((long)Math.floor(p.z)));
        values.put("frequency",RadioNavigation.frequency(p.kind,p.frequency));
        values.put("radius",number(p.radius));values.put("declination",number(p.declination));
        values.put("glide_slope",number(p.glideSlope));values.put("course",number(p.course));
        values.put("vor_mode",(p.vorMode==null?VorMode.VORDME:p.vorMode).name());
    }
    private void field(String key,String label){
        int y=top+rows++*30-offset;labels.put(label,y);
        if(y<top || y+28>bottom)return;
        var f=new TextFieldWidget(MinecraftClient.getInstance().textRenderer,10,y+10,host.side-20,17,Ui.t(key));
        f.setMaxLength(key.equals("ident")?80:24);f.setText(values.get(key));
        f.setChangedListener(v->values.put(key,v));f.setEditable(app.canEditShared());host.sideField(f);
    }
    void init(){
        Point p=selected();if(p==null)return;load(p);
        top=134;bottom=host.height-76;rows=0;labels.clear();
        host.listTop=top;host.listBottom=bottom;
        field("ident",Ui.tr("ui.airac.ident"));
        field("x","X");
        if(p.kind!=Kind.WAYPOINT)field("y","Y");
        field("z","Z");
        if(p.kind!=Kind.WAYPOINT)field("frequency",Ui.tr("ui.airac.frequency")+" / "+RadioNavigation.unit(p.kind));
        if(p.kind==Kind.VOR){
            int y=top+rows++*30-offset;labels.put(Ui.tr("ui.airac.vor_mode"),y);
            if(y>=top && y+28<=bottom)host.sideButton(y+10,Text.literal(values.get("vor_mode")),()->{
                VorMode mode=VorMode.valueOf(values.get("vor_mode"));
                values.put("vor_mode",VorMode.values()[(mode.ordinal()+1)%VorMode.values().length].name());host.refresh();
            },app.canEditShared());
            field("declination",Ui.tr("ui.airac.declination")+" / deg");
        }
        if(p.kind==Kind.ILS){
            field("glide_slope",Ui.tr("ui.airac.glide_slope")+" / deg");
            field("course",Ui.tr("ui.airac.course")+" / deg");
        }
        if(p.kind!=Kind.WAYPOINT)field("radius",Ui.tr("ui.airac.radius")+" / b");
        host.sideHalfButton(host.height-70,false,Ui.t("save"),this::save,app.canEditShared());
        host.sideHalfButton(host.height-70,true,Ui.t("delete"),()->host.confirm("confirm_delete_point",()->{
            app.chart.removePoint(p.id);reset();host.selectedPoint=null;app.changed();host.refresh();
        }),app.canEditShared());
        host.sideButton(host.height-50,Ui.t("done"),()->{reset();host.selectedPoint=null;host.refresh();},true);
    }
    private Point draft(){
        Point p=selected();if(p==null)return null;
        Point next=new Point();next.id=p.id;next.kind=p.kind;
        next.ident=values.get("ident").trim().toUpperCase(Locale.ROOT);
        if(next.ident.isEmpty() || next.ident.length()>80)throw new IllegalArgumentException("invalid_station");
        next.x=ChartData.blockCenter(Double.parseDouble(values.get("x").trim()));
        next.z=ChartData.blockCenter(Double.parseDouble(values.get("z").trim()));
        if(p.kind==Kind.WAYPOINT){
            if(!ChartData.coordinate(next.x) || !ChartData.coordinate(next.z))throw new IllegalArgumentException("invalid_station");
            return next;
        }
        next.y=Double.parseDouble(values.get("y").trim());
        next.frequency=RadioNavigation.parseFrequency(p.kind,values.get("frequency"));
        next.radius=Double.parseDouble(values.get("radius").trim());
        next.vorMode=VorMode.valueOf(values.get("vor_mode"));
        next.declination=Double.parseDouble(values.get("declination").trim());
        next.glideSlope=Double.parseDouble(values.get("glide_slope").trim());
        next.course=Double.parseDouble(values.get("course").trim());
        RadioNavigation.validateStation(next);return next;
    }
    Point preview(){
        if(!active() || id==null || selected().kind==Kind.WAYPOINT)return null;
        try{return draft();}catch(IllegalArgumentException ex){return selected();}
    }
    private void save(){
        if(!app.canEditShared())return;
        try{
            if(epoch!=app.chartEpoch)throw new IllegalArgumentException("sync_conflict");
            Point next=draft(),p=selected();if(p==null)return;
            if(app.chart.identifierUsed(next.ident,p.kind,p.id))throw new IllegalArgumentException("duplicate");
            p.ident=next.ident;p.x=next.x;p.y=next.y;p.z=next.z;p.frequency=next.frequency;
            p.radius=next.radius;p.vorMode=next.vorMode;p.declination=next.declination;p.glideSlope=next.glideSlope;p.course=next.course;
            error="";app.changed();host.refresh();
        }catch(NumberFormatException ex){error=Ui.tr("ui.airac.invalid_station");}
        catch(IllegalArgumentException ex){error=Ui.tr("ui.airac."+ex.getMessage());}
    }
    boolean scroll(double amount){
        if(!active())return false;
        int max=Math.max(0,rows*30-(bottom-top));
        offset=(int)Math.clamp(offset-amount*30,0,((max+29)/30)*30);
        host.refresh();return true;
    }
    void render(DrawContext c){
        c.enableScissor(7,top,host.side-3,Math.max(top,bottom));
        for(var entry:labels.entrySet())Ui.fit(c,entry.getKey(),10,entry.getValue(),host.side-20,Ui.MUTED);
        c.disableScissor();
        if(!error.isEmpty())Ui.scaledFit(c,error,10,host.height-82,host.side-20,0.7f,Ui.AMBER);
    }
}
