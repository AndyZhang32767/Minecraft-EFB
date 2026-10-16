package dev.airac.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;

public final class Ui {
    public static final int BG = 0xff101923, PANEL = 0xff172430, GRID = 0xff263947;
    public static final int TEXT = 0xffdae7ec, MUTED = 0xff8cabb8, CYAN = 0xff6ce1df;
    public static final int MAGENTA = 0xfff583dc, AMBER = 0xffffce80, GREEN = 0xff92e8ac;
    public static final int VOR_BLUE = 0xff409fff, NDB_RED = 0xffff5555;
    private Ui() {}
    public static String tr(String key, Object... args) { return I18n.translate(key, args); }
    public static Text t(String name) { return Text.translatable("ui.airac." + name); }
    public static void text(DrawContext c, String s, int x, int y, int color) {
        c.drawText(MinecraftClient.getInstance().textRenderer, s, x, y, color, false);
    }
    public static void fit(DrawContext c, String s, int x, int y, int width, int color) {
        var font = MinecraftClient.getInstance().textRenderer;
        text(c, font.trimToWidth(s, Math.max(0, width)), x, y, color);
    }
    public static void scaledFit(DrawContext c,String text,double x,double y,int width,float scale,int color) {
        var m=c.getMatrices();m.pushMatrix();m.translate((float)x,(float)y);m.scale(scale,scale);
        fit(c,text,0,0,(int)(width/scale),color);m.popMatrix();
    }
    /** Keep the baseline parallel to the segment, and readable in either travel direction. */
    public static void lineLabel(DrawContext c,String text,double x1,double y1,double x2,double y2,int color) {
        var font=MinecraftClient.getInstance().textRenderer;
        int width=font.getWidth(text);
        if(Math.hypot(x2-x1,y2-y1)<width*0.8+18)return;
        double angle=Math.atan2(y2-y1,x2-x1);
        if(angle>Math.PI/2)angle-=Math.PI;
        if(angle< -Math.PI/2)angle+=Math.PI;
        var m=c.getMatrices();m.pushMatrix();m.translate((float)((x1+x2)/2),(float)((y1+y2)/2));
        m.rotate((float)angle);m.scale(0.8f,0.8f);
        c.fill(-width/2-2,-11,width/2+3,0,0xd9081017);
        text(c,text,-width/2,-10,color);m.popMatrix();
    }
    public static void directionArrow(DrawContext c,double x1,double y1,double x2,double y2,int color) {
        double dx=x2-x1,dy=y2-y1,length=Math.hypot(dx,dy);if(length<28)return;
        double x=x1+dx*0.62,y=y1+dy*0.62,ux=dx/length,uy=dy/length;
        line(c,x-ux*6-uy*3,y-uy*6+ux*3,x,y,color);
        line(c,x-ux*6+uy*3,y-uy*6-ux*3,x,y,color);
    }
    public static void line(DrawContext c, double x1, double y1, double x2, double y2, int color) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        if (!Double.isFinite(length) || length > 20000) return;
        var matrices = c.getMatrices();
        matrices.pushMatrix();
        matrices.translate((float)x1, (float)y1);
        matrices.rotate((float)Math.atan2(y2 - y1, x2 - x1));
        c.fill(0, 0, Math.max(1, (int)Math.ceil(length)), 1, color);
        matrices.popMatrix();
    }
    public static void circle(DrawContext c, double x, double y, double r, int color) {
        arc(c, x, y, r, 0, 360, color);
    }
    public static void arc(DrawContext c, double x, double y, double r, double from, double to, int color) {
        for (double a = from; a < to; a += 4) {
            double b = Math.min(to, a + 4);
            line(c, x + Math.sin(Math.toRadians(a)) * r, y - Math.cos(Math.toRadians(a)) * r,
                    x + Math.sin(Math.toRadians(b)) * r, y - Math.cos(Math.toRadians(b)) * r, color);
        }
    }
    public static void diamond(DrawContext c, double x, double y, int r, int color) {
        line(c,x,y-r,x+r,y,color); line(c,x+r,y,x,y+r,color);
        line(c,x,y+r,x-r,y,color); line(c,x-r,y,x,y-r,color);
    }
    public static void station(DrawContext c,double x,double y,dev.airac.data.ChartData.Point p,int color){
        if(p.kind==dev.airac.data.ChartData.Kind.NDB){
            circle(c,x,y,5,color);circle(c,x,y,2,color);
        }else if(p.kind==dev.airac.data.ChartData.Kind.VOR){
            if(p.vorMode!=dev.airac.data.ChartData.VorMode.DME)
                for(int a=0;a<360;a+=60)line(c,x+Math.sin(Math.toRadians(a))*5,y-Math.cos(Math.toRadians(a))*5,
                        x+Math.sin(Math.toRadians(a+60))*5,y-Math.cos(Math.toRadians(a+60))*5,color);
            if(p.vorMode!=dev.airac.data.ChartData.VorMode.VOR){
                line(c,x-6,y-6,x+6,y-6,color);line(c,x+6,y-6,x+6,y+6,color);
                line(c,x+6,y+6,x-6,y+6,color);line(c,x-6,y+6,x-6,y-6,color);
            }
        }else{diamond(c,x,y,5,color);line(c,x-7,y,x+7,y,color);}
    }
    /** Fixed screen-size VOR ring; the inward tick indicates the station's north offset. */
    public static void vorNorthRing(DrawContext c,double x,double y,double declination,int color){
        circle(c,x,y,22,color);
        double angle=Math.toRadians(declination),dx=Math.sin(angle),dy=-Math.cos(angle);
        line(c,x+dx*9,y+dy*9,x+dx*22,y+dy*22,color);
    }
    /** One-pixel stroke centered on the segment. The shared line helper sits beside its axis. */
    private static void centeredLine(DrawContext c,double x1,double y1,double x2,double y2,int color) {
        double dx=x2-x1,dy=y2-y1,length=Math.hypot(dx,dy);
        if(!Double.isFinite(length) || length<0.5 || length>20000)return;
        double ux=dx/length,uy=dy/length;
        var m=c.getMatrices();m.pushMatrix();
        m.translate((float)(x1+uy*0.5f),(float)(y1-ux*0.5f));
        m.rotate((float)Math.atan2(dy,dx));
        c.fill(0,0,Math.max(1,(int)Math.ceil(length)),1,color);m.popMatrix();
    }
    /** A320 ND needle: shaft on the bearing, hollow triangle seated on the end. Radio 2 is the double bar. */
    public static void bearingNeedle(DrawContext c,double cx,double cy,double relative,double length,double tail,boolean doubled,int color,
                                     double left,double top,double right,double bottom) {
        double rel=Math.toRadians(relative),sx=Math.sin(rel),sy=-Math.cos(rel),px=-sy,py=sx;
        double tipX=cx+sx*length,tipY=cy+sy*length,head=Math.max(9,length*0.09),half=head*0.62;
        double baseX=tipX-sx*head,baseY=tipY-sy*head;
        double tailX=cx-sx*tail,tailY=cy-sy*tail,gap=1.5;
        if(doubled){
            clippedCentered(c,tailX+px*gap,tailY+py*gap,baseX+px*gap,baseY+py*gap,left,top,right,bottom,color);
            clippedCentered(c,tailX-px*gap,tailY-py*gap,baseX-px*gap,baseY-py*gap,left,top,right,bottom,color);
        }else clippedCentered(c,tailX,tailY,baseX,baseY,left,top,right,bottom,color);
        clippedCentered(c,tipX,tipY,baseX+px*half,baseY+py*half,left,top,right,bottom,color);
        clippedCentered(c,tipX,tipY,baseX-px*half,baseY-py*half,left,top,right,bottom,color);
        clippedCentered(c,baseX+px*half,baseY+py*half,baseX-px*half,baseY-py*half,left,top,right,bottom,color);
    }
    /** Rear-VOR line: 0.4 radius, outer end on the compass ring. Radio 2 is a centered double bar. */
    public static void bearingLine(DrawContext c,double cx,double cy,double relative,double radius,boolean doubled,int color,
                                   double left,double top,double right,double bottom) {
        double rel=Math.toRadians(relative),sx=Math.sin(rel),sy=-Math.cos(rel),px=-sy,py=sx;
        double x1=cx+sx*radius*0.6,y1=cy+sy*radius*0.6,x2=cx+sx*radius,y2=cy+sy*radius,gap=1.5;
        if(doubled){
            clippedCentered(c,x1+px*gap,y1+py*gap,x2+px*gap,y2+py*gap,left,top,right,bottom,color);
            clippedCentered(c,x1-px*gap,y1-py*gap,x2-px*gap,y2-py*gap,left,top,right,bottom,color);
        }else clippedCentered(c,x1,y1,x2,y2,left,top,right,bottom,color);
    }
    /** ARC pointer: hollow triangle on the compass arc, stem running 0.4 radius in from the ring. */
    public static void arcPointer(DrawContext c,double cx,double cy,double relative,double radius,boolean doubled,int color,
                                  double left,double top,double right,double bottom) {
        double rel=Math.toRadians(relative),sx=Math.sin(rel),sy=-Math.cos(rel),px=-sy,py=sx;
        double apexX=cx+sx*radius,apexY=cy+sy*radius,back=9,half=5,gap=1.5;
        double baseX=apexX-sx*back,baseY=apexY-sy*back;
        double endX=cx+sx*radius*0.6,endY=cy+sy*radius*0.6;
        clippedCentered(c,apexX,apexY,baseX+px*half,baseY+py*half,left,top,right,bottom,color);
        clippedCentered(c,apexX,apexY,baseX-px*half,baseY-py*half,left,top,right,bottom,color);
        clippedCentered(c,baseX+px*half,baseY+py*half,baseX-px*half,baseY-py*half,left,top,right,bottom,color);
        if(doubled){
            clippedCentered(c,baseX+px*gap,baseY+py*gap,endX+px*gap,endY+py*gap,left,top,right,bottom,color);
            clippedCentered(c,baseX-px*gap,baseY-py*gap,endX-px*gap,endY-py*gap,left,top,right,bottom,color);
        }else clippedCentered(c,baseX,baseY,endX,endY,left,top,right,bottom,color);
    }
    /** Keep the visible portion of a stroke. A segment that only crosses the ND edge must not vanish. */
    private static void clippedCentered(DrawContext c,double x1,double y1,double x2,double y2,
                                        double left,double top,double right,double bottom,int color) {
        double dx=x2-x1,dy=y2-y1,lo=0,hi=1;
        double[] p={-dx,dx,-dy,dy},q={x1-left,right-x1,y1-top,bottom-y1};
        for(int i=0;i<4;i++){
            if(p[i]==0){if(q[i]<0)return;}
            else{double t=q[i]/p[i];if(p[i]<0)lo=Math.max(lo,t);else hi=Math.min(hi,t);if(lo>hi)return;}
        }
        centeredLine(c,x1+lo*dx,y1+lo*dy,x1+hi*dx,y1+hi*dy,color);
    }
    /** A320 ND reference aircraft: a cross, nose along -Y before the heading rotation. */
    public static void aircraft(DrawContext c, double x, double y, double heading, int color) {
        var m = c.getMatrices(); m.pushMatrix(); m.translate((float)x, (float)y);
        m.rotate((float)Math.toRadians(heading));
        centeredLine(c,0,-9,0,9,color);centeredLine(c,-8,0,8,0,color);m.popMatrix();
    }
    /** Chart symbol: fuselage, wings above center, and a shorter tail. */
    public static void mapAircraft(DrawContext c, double x, double y, double heading, int color) {
        var m = c.getMatrices(); m.pushMatrix(); m.translate((float)x, (float)y);
        m.rotate((float)Math.toRadians(heading));
        centeredLine(c,0,-9,0,8,color);centeredLine(c,-7,-2,7,-2,color);centeredLine(c,-3,7,3,7,color);m.popMatrix();
    }
    /** Clip a line before submitting it; far-away world coordinates stay bounded. */
    public static void clippedLine(DrawContext c, double x1, double y1, double x2, double y2,
                                   double left, double top, double right, double bottom, int color) {
        double dx = x2-x1, dy = y2-y1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1-left, right-x1, y1-top, bottom-y1};
        double lo=0, hi=1;
        for (int i=0;i<4;i++) {
            if (p[i]==0) { if(q[i]<0) return; }
            else {
                double t=q[i]/p[i];
                if(p[i]<0) lo=Math.max(lo,t); else hi=Math.min(hi,t);
                if(lo>hi) return;
            }
        }
        line(c,x1+lo*dx,y1+lo*dy,x1+hi*dx,y1+hi*dy,color);
    }
    public static void circleLine(DrawContext c, double x1,double y1,double x2,double y2,double cx,double cy,double radius,int color) {
        double dx=x2-x1,dy=y2-y1,fx=x1-cx,fy=y1-cy;
        double a=dx*dx+dy*dy;
        if(a<0.001) return;
        double b=2*(fx*dx+fy*dy), d=b*b-4*a*(fx*fx+fy*fy-radius*radius);
        if(d<0) return;
        double lo=Math.max(0,(-b-Math.sqrt(d))/(2*a)), hi=Math.min(1,(-b+Math.sqrt(d))/(2*a));
        if(lo<=hi) line(c,x1+lo*dx,y1+lo*dy,x1+hi*dx,y1+hi*dy,color);
    }
}
