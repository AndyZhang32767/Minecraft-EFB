package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.ChartData.*;
import dev.airac.nav.AirwayEditor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.util.*;

/** Map-based public airway editing shares the flight plan's gap controls. */
final class AirwaySidebar {
    private final ChartScreen host;
    private final AiracClient app=AiracClient.INSTANCE;
    private String id,signature="";
    private int cursor=-2,selected=-1;
    private record Row(int kind,int index) {} // 0 fix, 1 gap, 2 head, 3 tail
    AirwaySidebar(ChartScreen host){this.host=host;}
    private Airway airway(){return app.chart.airways.stream().filter(a->a.id.equals(id)).findFirst().orElse(null);}
    boolean active(){return host.isAirway() && airway()!=null;}
    void reset(){id=null;cursor=-2;selected=-1;}
    void open(Airway a,boolean append){id=a.id;host.selectedAirway=a.id;cursor=append?-1:-2;selected=-1;signature=signature(a);host.scroll=0;}
    private String signature(Airway a){return a.pointIds.toString()+a.discontinuities;}
    void updated(){
        Airway a=airway();if(a==null){reset();return;}
        if(!signature.equals(signature(a))){cursor=-2;selected=-1;signature=signature(a);}
    }
    private void changed(){signature=signature(airway());app.changed();host.refresh();}
    private List<Row> rows(){
        List<Row> rows=new ArrayList<>();Airway a=airway();if(a==null)return rows;
        rows.add(new Row(2,0));
        for(int i=0;i<a.pointIds.size();i++) {
            if(a.disconnectedBefore(i))rows.add(new Row(1,i));
            rows.add(new Row(0,i));
        }
        rows.add(new Row(3,a.pointIds.size()));return rows;
    }
    void init(){
        host.listTop=134;host.listBottom=host.height-94;
        host.sideHalfButton(host.height-90,false,Ui.t("edit"),host::editSelectedAirway,app.canEditShared());
        host.sideHalfButton(host.height-90,true,Ui.t("remove"),()->{
            Airway a=airway();AirwayEditor.remove(a,selected);selected=-1;cursor=-2;changed();
        },app.canEditShared() && selected>=0 && airway().pointIds.size()>1);
        host.sideHalfButton(host.height-70,false,Ui.t("delete"),()->host.confirm("confirm_delete",()->{
            app.chart.airways.removeIf(a->a.id.equals(id));reset();host.selectedAirway=null;app.changed();host.refresh();
        }),app.canEditShared());
        host.sideHalfButton(host.height-70,true,Ui.t("new_airway"),()->{reset();host.beginAirway();},app.canEditShared());
        host.sideButton(host.height-50,Ui.t("done"),()->{reset();host.beginAirway();},true);
    }
    boolean mapPick(Point point,int button){
        if(!active())return false;
        if(button!=0 || point==null || cursor==-2 || !app.canEditShared())return true;
        try {
            Airway a=airway();cursor=AirwayEditor.insert(app.chart,a,cursor,point.id);
            selected=cursor==-1?a.pointIds.size()-1:cursor>=0?cursor-1:-1;
            host.selectedPoint=point.id;changed();
        }catch(IllegalArgumentException ex){host.airwayHint(ex.getMessage());}
        return true;
    }
    boolean click(double x,double y){
        if(!active())return false;
        var rows=rows();int index=(int)(y-host.listTop)/16+host.scroll;
        if(index<0 || index>=rows.size())return true;
        Row row=rows.get(index);Airway a=airway();
        if(row.kind==0){selected=row.index;host.selectedPoint=a.pointIds.get(selected);host.refresh();return true;}
        if(!app.canEditShared())return true;
        if(row.kind==2)cursor=0;
        else if(row.kind==3)cursor=-1;
        else if(x<26)cursor=row.index;
        else if(x>host.side-24){AirwayEditor.connect(a,row.index);cursor=-2;changed();}
        return true;
    }
    void render(DrawContext c){
        Airway a=airway();
        Ui.fit(c,a.name,10,99,host.side-20,a.oneWay?Ui.VOR_BLUE:Ui.CYAN);
        var rows=rows();int visible=Math.max(0,(host.listBottom-host.listTop)/16);
        host.scroll=Math.clamp(host.scroll,0,Math.max(0,rows.size()-visible));
        c.enableScissor(7,host.listTop,host.side-3,Math.max(host.listTop,host.listBottom));
        for(int i=host.scroll;i<Math.min(rows.size(),host.scroll+visible);i++) {
            Row row=rows.get(i);int y=host.listTop+(i-host.scroll)*16;
            if(row.kind==1){RouteRows.controls(c,8,y,host.side-6,cursor==row.index,"---DISCONTINUITY---");continue;}
            if(row.kind==0 && selected==row.index || row.kind==2 && cursor==0 || row.kind==3 && cursor==-1)
                c.fill(7,y-2,host.side-3,y+13,0xff304954);
            String label;
            if(row.kind==2)label="+ "+Ui.tr("ui.airac.airway_start");
            else if(row.kind==3)label="+ "+Ui.tr("ui.airac.append_end");
            else {Point p=app.chart.point(a.pointIds.get(row.index));label=String.format(Locale.ROOT,"%02d  %s",row.index+1,p==null?"?":p.ident);}
            Ui.fit(c,label,11,y+2,host.side-25,Ui.CYAN);
        }
        c.disableScissor();
    }
}
