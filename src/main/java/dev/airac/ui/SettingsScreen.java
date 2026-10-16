package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.NdSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.Locale;

public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final AiracClient app=AiracClient.INSTANCE;
    public SettingsScreen(Screen parent) { super(Ui.t("nd_settings"));this.parent=parent; }
    private void button(int y,String label,Runnable action) {
        addDrawableChild(new FlatButton(width/2-120,y,240,20,net.minecraft.text.Text.literal(label),b->{action.run();app.saveSettings();clearAndInit();}));
    }
    private void adjust(int y,String label,String key,Runnable less,Runnable more,boolean canLess,boolean canMore) {
        var minus=addDrawableChild(new FlatButton(width/2-150,y,48,20,Ui.t(key+"_down"),b->{less.run();app.saveSettings();clearAndInit();}));
        var plus=addDrawableChild(new FlatButton(width/2+102,y,48,20,Ui.t(key+"_up"),b->{more.run();app.saveSettings();clearAndInit();}));
        minus.active=canLess;plus.active=canMore;
        var value=addDrawableChild(new FlatButton(width/2-100,y,200,20,Text.literal(label),b->{}));
        value.active=false;
    }
    @Override protected void init() {
        var s=app.settings;int y=Math.max(18,height/2-148),step=Math.min(22,Math.max(16,(height-y-29)/9));
        button(y,Ui.tr("ui.airac.mode")+": "+s.mode,s::toggleMode);
        button(y+step,Ui.tr("ui.airac.position")+": "+Ui.tr("ui.airac."+s.corner.name().toLowerCase(Locale.ROOT)),s::cycleCorner);
        button(y+step*2,Ui.tr("ui.airac.visibility",Ui.tr("ui.airac."+s.visibility.name().toLowerCase(Locale.ROOT))),s::cycleVisibility);
        button(y+step*3,Ui.tr("ui.airac.auto_range")+": "+Ui.tr(s.autoRange?"ui.airac.on":"ui.airac.off"),()->s.autoRange=!s.autoRange);
        adjust(y+step*4,s.autoRange?"AUTO R128-R4096 (ALT 150-400)":Ui.tr("ui.airac.range")+": "+s.range()+" b","range",()->s.range(-1),()->s.range(1),!s.autoRange && s.rangeIndex>0,!s.autoRange && s.rangeIndex<dev.airac.data.NdSettings.RANGES.length-1);
        adjust(y+step*5,Ui.tr("ui.airac.size")+": "+s.size,"size",()->s.resizePreset(-1),()->s.resizePreset(1),s.size>100,s.size<360);
        button(y+step*6,Ui.tr("ui.airac.capture")+": "+(int)s.captureRadius+" b",()->s.captureRadius=s.captureRadius>=64?8:s.captureRadius*2);
        addDrawableChild(new FlatButton(width/2-152,y+step*7,148,20,Text.literal(s.ndRadio1?Ui.tr("ui.airac.avail"):Ui.tr("ui.airac.radio_1")),b->{s.ndRadio1=!s.ndRadio1;app.saveSettings();clearAndInit();}).color(s.ndRadio1?Ui.GREEN:Ui.NDB_RED));
        addDrawableChild(new FlatButton(width/2+4,y+step*7,148,20,Text.literal(s.ndRadio2?Ui.tr("ui.airac.avail"):Ui.tr("ui.airac.radio_2")),b->{s.ndRadio2=!s.ndRadio2;app.saveSettings();clearAndInit();}).color(s.ndRadio2?Ui.GREEN:Ui.NDB_RED));
        String[] layers={"WPT","VOR.D","NDB","APRT","CSTR"};
        NdSettings.Reference[] refs={NdSettings.Reference.WPT,NdSettings.Reference.VORD,NdSettings.Reference.NDB,NdSettings.Reference.APRT,null};
        for(int i=0;i<layers.length;i++){
            int index=i;
            boolean on=refs[i]==null?s.cstr:s.reference==refs[i];
            addDrawableChild(new FlatButton(width/2-155+i*62,y+step*8,60,20,Text.literal(on?">"+layers[i]+"<":layers[i]),b->{
                if(refs[index]==null)s.cstr=!s.cstr; else s.selectReference(refs[index]);
                app.saveSettings();clearAndInit();
            }).color(on?Ui.GREEN:Ui.MUTED));
        }
        addDrawableChild(new FlatButton(width/2-120,y+step*9+4,240,20,Ui.t("done"),b->close()));
    }
    @Override public void render(DrawContext c,int mx,int my,float delta) {
        c.fill(0,0,width,height,Ui.BG);c.drawCenteredTextWithShadow(textRenderer,title,width/2,13,Ui.CYAN);super.render(c,mx,my,delta);
    }
    @Override public void close() { if(client!=null) client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
