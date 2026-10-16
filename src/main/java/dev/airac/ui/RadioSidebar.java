package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.ChartData.*;
import dev.airac.nav.NavMath;
import dev.airac.nav.RadioNavigation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.Locale;

/** Two independent, personal radio receivers. Public chart permissions never gate tuning. */
final class RadioSidebar {
    private final ChartScreen host;
    private final AiracClient app=AiracClient.INSTANCE;
    private final String[] values={null,null}, errors={"",""};
    private static final Kind[] MODES={Kind.NDB,Kind.VOR,Kind.ILS};
    private int offset;
    RadioSidebar(ChartScreen host){this.host=host;}
    void reset(){values[0]=values[1]=null;errors[0]=errors[1]="";offset=0;}
    private void cycle(int index){
        Radio radio=app.chart.radios.get(index);
        radio.mode=MODES[(java.util.Arrays.asList(MODES).indexOf(radio.mode)+1)%MODES.length];
        radio.frequency=0;values[index]="";errors[index]="";app.save();host.refresh();
    }
    private int block(int i){return 114+i*190-offset;}
    private boolean manual(int i){return i!=0 || !app.chart.radios.getFirst().auto;}
    void init(){
        host.listTop=114;host.listBottom=host.height-34;
        for(int i=0;i<2;i++){
            int index=i,y=block(i);Radio radio=app.chart.radios.get(i);boolean editable=app.canEditPlans() && manual(i);
            if(values[i]==null)values[i]=RadioNavigation.frequency(radio.mode,radio.frequency);
            if(i==0 && y+12>=114 && y+30<=host.listBottom)
                host.sideButton(y+12,Text.literal(radio.auto?Ui.tr("ui.airac.auto_short"):Ui.tr("ui.airac.manual")),()->{
                    radio.auto=!radio.auto;errors[0]="";
                    if(radio.auto && !(dev.airac.compat.FlightAssistantIntegration.active() && radio.mode==Kind.ILS)){
                        var player=MinecraftClient.getInstance().player;
                        if(player!=null)RadioNavigation.tuneNearestVor(radio,app.chart,player.getX(),player.getZ());
                    }
                    values[0]=RadioNavigation.frequency(radio.mode,radio.frequency);
                    app.save();host.refresh();
                },app.canEditPlans());
            int mode=i==0?34:14,fieldY=i==0?65:45,tuneY=i==0?87:67;
            if(y+mode>=114 && y+mode+18<=host.listBottom)
                host.sideButton(y+mode,Text.literal(radio.mode.name()),()->cycle(index),editable);
            if(y+fieldY>=114 && y+fieldY+17<=host.listBottom){
                var field=new TextFieldWidget(MinecraftClient.getInstance().textRenderer,10,y+fieldY,host.side-20,17,Text.literal(Ui.tr("ui.airac.radio_field",i+1)));
                field.setMaxLength(16);field.setText(values[i]);field.setChangedListener(v->values[index]=v);
                field.setEditable(editable);host.sideField(field);
            }
            if(y+tuneY>=114 && y+tuneY+18<=host.listBottom){
                host.sideHalfButton(y+tuneY,false,Ui.t("tune"),()->tune(index),editable);
                host.sideHalfButton(y+tuneY,true,Ui.t("tune_clear"),()->{
                    radio.frequency=0;values[index]="";errors[index]="";app.save();host.refresh();
                },editable);
            }
        }
    }
    private void tune(int index){
        try{
            Radio radio=app.chart.radios.get(index);
            int frequency=RadioNavigation.parseFrequency(radio.mode,values[index]);
            radio.frequency=frequency;values[index]=RadioNavigation.frequency(radio.mode,frequency);
            errors[index]="";app.save();host.refresh();
        }catch(IllegalArgumentException ex){errors[index]=Ui.tr("ui.airac.invalid_radio_frequency");}
    }
    boolean scroll(double amount){
        offset=(int)Math.clamp(offset-amount*30,0,Math.max(0,492-(host.height-34)));
        host.refresh();return true;
    }
    void render(DrawContext c){
        var client=MinecraftClient.getInstance();
        Ui.fit(c,Ui.tr("ui.airac.tool_radio"),10,83,host.side-20,Ui.CYAN);
        c.enableScissor(7,114,host.side-3,Math.max(114,host.height-34));
        for(int i=0;i<2;i++){
            int y=block(i);Radio radio=app.chart.radios.get(i);
            Ui.text(c,Ui.tr(i==0?"ui.airac.radio_1":"ui.airac.radio_2"),10,y,Ui.CYAN);
            Ui.text(c,Ui.tr("ui.airac.freq")+" / "+RadioNavigation.unit(radio.mode),10,y+(i==0?55:35),Ui.MUTED);
            var signal=client.player==null?null:RadioNavigation.receive(app.chart,radio,client.player.getX(),client.player.getY(),
                    client.player.getZ(),NavMath.heading(client.player.getYaw()));
            String status=!errors[i].isEmpty()?errors[i]:radio.frequency==0?"NOT TUNED":signal==null?"NO SIGNAL":
                    signal.station().ident+" / "+(radio.mode==Kind.VOR?signal.station().vorMode.name():radio.mode.name());
            int info=y+(i==0?111:91);
            Ui.fit(c,status,10,info,host.side-20,signal==null?Ui.AMBER:Ui.GREEN);
            Ui.fit(c,RadioNavigation.frequency(radio.mode,radio.frequency)+" "+RadioNavigation.unit(radio.mode),
                    10,info+12,host.side-20,Ui.MUTED);
            if(signal!=null){
                if(signal.station().kind==Kind.NDB)
                    line(c,String.format(Locale.ROOT,"BRG %03.0f  REL %03.0f",signal.bearing(),signal.relativeBearing()),info+25);
                if(signal.station().kind==Kind.VOR){
                    if(signal.hasBearing())line(c,String.format(Locale.ROOT,"RDL %03.0f  BRG %03.0f",signal.radial(),signal.bearing()),info+25);
                    if(signal.hasDme())line(c,String.format(Locale.ROOT,"DME %.1f b",signal.distance()),info+37);
                }
                if(signal.station().kind==Kind.ILS){
                    line(c,String.format(Locale.ROOT,"CRS %03.0f  GS %.1f",signal.station().course,signal.station().glideSlope),info+25);
                    line(c,String.format(Locale.ROOT,"LOC %+.2f  GS %+.2f",signal.localizer(),signal.glideSlope()),info+37);
                }
            }
        }
        c.disableScissor();
    }
    private void line(DrawContext c,String text,int y){Ui.scaledFit(c,text,10,y,host.side-20,0.8f,Ui.TEXT);}
}
