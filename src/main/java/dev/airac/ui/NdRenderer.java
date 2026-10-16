package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.*;
import dev.airac.data.ChartData.Kind;
import dev.airac.nav.NavMath;
import dev.airac.nav.NeedleSlew;
import dev.airac.nav.PerformanceProfile;
import dev.airac.nav.RadioNavigation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import java.util.*;

public final class NdRenderer {
    private static final double[] needleBearing={Double.NaN,Double.NaN};
    private static long needleNanos;
    private NdRenderer() {}
    public static void render(DrawContext c, AiracClient app) {
        var client=MinecraftClient.getInstance();
        if(client.player==null || client.world==null || client.options.hudHidden || client.currentScreen!=null) return;
        NdSettings s=app.settings;
        double speed=client.player.getVelocity().horizontalLength()*20;
        double range=s.rangeAtAltitude(client.player.getY());
        if(s.visibility==NdSettings.Visibility.HIDDEN || (s.visibility==NdSettings.Visibility.FLYING && !app.flying(client))) return;
        int size=Math.min(s.size, Math.min(c.getScaledWindowWidth()-16,c.getScaledWindowHeight()-16));
        if(size<80) return;
        boolean right=s.corner==NdSettings.Corner.TOP_RIGHT || s.corner==NdSettings.Corner.BOTTOM_RIGHT;
        boolean bottom=s.corner==NdSettings.Corner.BOTTOM_LEFT || s.corner==NdSettings.Corner.BOTTOM_RIGHT;
        int left=right ? c.getScaledWindowWidth()-size-8 : 8;
        int top=bottom ? c.getScaledWindowHeight()-size-8 : 8;
        c.fill(left,top,left+size,top+size,0xeb081017);
        c.fill(left,top,left+size,top+1,Ui.CYAN);
        double unit=Math.min(1,size/190.0);
        float fontScale=(float)Math.clamp(size/260.0,0.38,0.85);
        int padding=Math.max(4,(int)Math.round(7*unit));
        Ui.scaledFit(c,Ui.tr("ui.airac.mode_"+s.mode.name().toLowerCase(Locale.ROOT)),left+padding,top+7*unit,size/3,fontScale,Ui.GREEN);
        Ui.scaledFit(c,"RNG "+Math.round(range)+" b"+(s.autoRange?" "+Ui.tr("ui.airac.auto_short"):""),left+padding,top+18*unit,size-padding*2,fontScale,Ui.MUTED);
        double heading=NavMath.heading(client.player.getYaw());
        String headingText=String.format(Locale.ROOT,"%03.0f",heading);
        Ui.scaledFit(c,headingText,left+size/2.0-client.textRenderer.getWidth(headingText)*fontScale/2,top+7*unit,size/3,fontScale,Ui.TEXT);
        boolean arc=s.mode==NdSettings.Mode.ARC;
        boolean map=s.mapMode();
        double cx=left+size/2.0, cy=top+size*(arc ? 0.77 : 0.52);
        double radius=size*(arc ? 0.58 : 0.31);
        double scale=radius/range;
        int clipTop=top+(int)Math.ceil(29*unit),clipBottom=top+size-(int)Math.ceil(26*unit);
        List<int[]> labels=new ArrayList<>();
        labels.add(new int[]{(int)(cx-8*unit),(int)(cy-8*unit),(int)Math.ceil(cx+8*unit),(int)Math.ceil(cy+9*unit)});
        c.enableScissor(left+4,clipTop,left+size-4,clipBottom);
        Ui.arc(c,cx,cy,radius,arc?-90:0,arc?90:360,Ui.MUTED);
        Ui.arc(c,cx,cy,radius/2,arc?-90:0,arc?90:360,Ui.GRID);
        for(int angle=0;angle<360;angle+=10) {
            double relative=NavMath.normalize(angle-heading+180)-180;
            if(arc && Math.abs(relative)>90)continue;
            double rel=Math.toRadians(relative);
            double x=cx+Math.sin(rel)*radius,y=cy-Math.cos(rel)*radius;
            double inner=radius-(angle%30==0?6:3)*unit;
            Ui.line(c,x,y,cx+Math.sin(rel)*inner,cy-Math.cos(rel)*inner,Ui.MUTED);
            if(angle%30==0) {
                String label=switch(angle) { case 0->"N";case 90->"E";case 180->"S";case 270->"W";default->Integer.toString(angle/10);};
                int labelWidth=(int)Math.ceil(client.textRenderer.getWidth(label)*fontScale);
                int labelHeight=(int)Math.ceil(9*fontScale);
                int lx=(int)(cx+Math.sin(rel)*(radius-14*unit)-labelWidth/2.0);
                int ly=(int)(cy-Math.cos(rel)*(radius-14*unit)-labelHeight/2.0);
                if(lx>=left+padding && lx+labelWidth<left+size-padding && ly>=clipTop && ly+labelHeight<clipBottom) {
                    Ui.scaledFit(c,label,lx,ly,24,fontScale,Ui.TEXT);
                    labels.add(new int[]{lx,ly,lx+labelWidth,ly+labelHeight});
                }
            }
        }
        Ui.diamond(c,cx,cy-radius,Math.max(3,(int)Math.round(3.5*unit)),Ui.GREEN);
        double px=client.player.getX(),pz=client.player.getZ();
        ChartData chart=app.chart;
        ChartData.Route plan=chart.activeRoute();
        // Only the selected flight plan is drawn. Permanent chart airways are intentionally independent.
        if(map && plan!=null) for(int i=1;i<plan.pointIds.size();i++) {
            var a=chart.point(plan.pointIds.get(i-1));var b=chart.point(plan.pointIds.get(i));
            if(a==null || b==null || plan.disconnectedBefore(i)) continue;
            var av=NavMath.project(a.x-px,a.z-pz,heading,scale);
            var bv=NavMath.project(b.x-px,b.z-pz,heading,scale);
            Ui.circleLine(c,cx+av.x(),cy+av.y(),cx+bv.x(),cy+bv.y(),cx,cy,radius,
                    i<chart.activeLeg?0xff6d506c:Ui.MAGENTA);
        }
        var target=chart.target();
        if(map && target!=null) {
            var v=NavMath.project(target.x-px,target.z-pz,heading,scale);
            Ui.circleLine(c,cx,cy,cx+v.x(),cy+v.y(),cx,cy,radius,Ui.CYAN);
        }
        // Current target gets first choice of label position; symbols always remain visible.
        Map<String,Integer> constraints=constraints(s,chart,plan,client.player.getY());
        long now=System.nanoTime();
        double dt=needleNanos==0?0:Math.min(0.1,(now-needleNanos)*1e-9);needleNanos=now;
        boolean[] ndRadio={s.ndRadio1,s.ndRadio2};
        Set<String> pointed=new HashSet<>();
        record Cue(int radio,int cue,double aim,double relative) {}
        List<Cue> cues=new ArrayList<>();
        for(int i=0;i<2;i++){
            if(!ndRadio[i])continue;
            var signal=RadioNavigation.receive(chart,chart.radios.get(i),px,client.player.getY(),pz,heading);
            if(signal==null || signal.station().kind!=Kind.VOR || !signal.hasBearing())continue;
            double bearing=NavMath.bearing(signal.station().x-px,signal.station().z-pz);
            needleBearing[i]=NeedleSlew.step(needleBearing[i],bearing,140,dt);
            double relative=NavMath.normalize(needleBearing[i]-heading+180)-180;
            int cue=NavMath.vorCue(relative);
            if(cue<0)continue;
            pointed.add(signal.station().id);
            cues.add(new Cue(i,cue,cue>0?NavMath.reciprocal(relative):relative,relative));
        }
        List<ChartData.Point> points=map?new ArrayList<>(chart.points):new ArrayList<>();
        points.sort(Comparator.comparingInt(p->p==target?0:plan!=null && plan.pointIds.contains(p.id)?1:2));
        for(var p:points) {
            boolean inPlan=plan!=null && plan.pointIds.contains(p.id);
            boolean tuned=pointed.contains(p.id);
            if(!tuned && !s.shows(p.kind,inPlan)) continue;
            var v=NavMath.project(p.x-px,p.z-pz,heading,scale);
            double sx=cx+v.x(),sy=cy+v.y();
            if(tuned){if(sx<left+4 || sx>left+size-4 || sy<clipTop || sy>clipBottom)continue;}
            else if(Math.hypot(v.x(),v.y())>radius-9) continue;
            int color=tuned||p==target?Ui.CYAN:inPlan?Ui.MAGENTA:Ui.MUTED;
            int symbolRadius=Math.max(2,(int)Math.round(3*unit));
            if(p.kind==ChartData.Kind.AIRPORT) Ui.circle(c,sx,sy,symbolRadius,color);
            else Ui.diamond(c,sx,sy,symbolRadius,color);
            int x=(int)sx,y=(int)sy;
            int labelWidth=Math.min((int)Math.ceil(client.textRenderer.getWidth(p.ident)*fontScale),size/2);
            int labelHeight=(int)Math.ceil(9*fontScale);
            int[][] positions={{x+5,y-labelHeight/2},{x-labelWidth-5,y-labelHeight/2},{x+5,y-labelHeight-5},{x+5,y+5}};
            for(int[] position:positions){
                int lx=position[0],ly=position[1];
                if(lx<left+5 || lx+labelWidth>left+size-5 || ly<clipTop || ly+labelHeight>clipBottom)continue;
                boolean overlaps=false;
                for(int[] box:labels)if(lx<box[2]+2 && lx+labelWidth+2>box[0] && ly<box[3]+2 && ly+labelHeight+2>box[1]){overlaps=true;break;}
                if(overlaps)continue;
                Ui.scaledFit(c,p.ident,lx,ly,labelWidth,fontScale,color);labels.add(new int[]{lx,ly,lx+labelWidth,ly+labelHeight});
                Integer altitude=constraints.get(p.id);
                if(altitude!=null){
                    String constraint=Integer.toString(altitude);
                    int cw=(int)Math.ceil(client.textRenderer.getWidth(constraint)*fontScale);
                    Ui.scaledFit(c,constraint,lx,ly-labelHeight-1,cw,fontScale,Ui.MAGENTA);
                }
                break;
            }
        }
        // The pointer is laid out past the ND. Submit only the slice inside the window, without the
        // panel scissor dropping a stroke that merely crosses the edge.
        double clipLeft=left+4,clipTopD=clipTop,clipRight=left+size-4,clipBottomD=clipBottom;
        c.disableScissor();
        for(var cue:cues){
            if(cue.cue>0){
                double aim=cue.aim;
                Ui.bearingLine(c,cx,cy,arc?Math.clamp(aim,-90,90):aim,radius,cue.radio==1,Ui.CYAN,clipLeft,clipTopD,clipRight,clipBottomD);continue;
            }
            if(arc) Ui.arcPointer(c,cx,cy,Math.clamp(cue.relative,-90,90),radius,cue.radio==1,Ui.CYAN,clipLeft,clipTopD,clipRight,clipBottomD);
            else Ui.bearingNeedle(c,cx,cy,cue.relative,radius,radius*0.4,cue.radio==1,Ui.CYAN,clipLeft,clipTopD,clipRight,clipBottomD);
        }
        c.enableScissor(left+4,clipTop,left+size-4,clipBottom);
        var matrices=c.getMatrices();matrices.pushMatrix();
        matrices.translate((float)cx,(float)cy);matrices.scale((float)unit,(float)unit);
        if(arc)Ui.mapAircraft(c,0,0,0,Ui.TEXT);else Ui.aircraft(c,0,0,0,Ui.TEXT);matrices.popMatrix();
        c.disableScissor();
        c.fill(left+1,clipBottom+1,left+size-1,top+size-1,0xff0e1921);
        for(int i=0;i<2;i++){
            if(!ndRadio[i])continue;
            var signal=RadioNavigation.receive(chart,chart.radios.get(i),px,client.player.getY(),pz,heading);
            if(signal==null || signal.station().kind!=Kind.VOR && signal.station().kind!=Kind.NDB)continue;
            int color=signal.station().kind==Kind.NDB?Ui.NDB_RED:Ui.CYAN;
            String text=signal.station().ident;
            if(signal.hasDme())text=String.format(Locale.ROOT,"%s %.0fb",text,Math.hypot(signal.station().x-px,signal.station().z-pz));
            int max=Math.max(24,size/2-padding);
            double textX=left+padding;
            if(i==1){
                int width=(int)Math.ceil(client.textRenderer.getWidth(text)*fontScale);
                textX=left+size-padding-Math.min(width,max);
            }
            Ui.scaledFit(c,text,textX,clipBottom+4*unit,max,fontScale,color);
        }
        String gsAlt=String.format(Locale.ROOT,"GS %.1f b/s   ALT %.0f",speed,client.player.getY());
        int gsWidth=(int)Math.ceil(client.textRenderer.getWidth(gsAlt)*fontScale);
        Ui.scaledFit(c,gsAlt,left+Math.max(padding,(size-Math.min(gsWidth,size-padding*2))/2.0),clipBottom+16*unit,size-padding*2,fontScale,Ui.TEXT);
    }
    private static Map<String,Integer> constraints(NdSettings s,ChartData chart,ChartData.Route plan,double altitude) {
        if(!s.cstr || !s.mapMode() || plan==null)return Map.of();
        PerformanceProfile profile=PerformanceProfile.preview(chart,plan,altitude);
        if(profile==null)return Map.of();
        Map<String,Integer> values=new HashMap<>();
        for(var node:profile.nodes)if(node.pointId()!=null)values.put(node.pointId(),(int)Math.round(node.altitude()));
        return values;
    }
}
