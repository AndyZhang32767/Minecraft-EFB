package dev.airac.ui;

import dev.airac.AiracClient;
import dev.airac.data.ChartData.*;
import dev.airac.nav.RadioNavigation;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;

/** Four performance columns edit the selected personal plan, committing all fields together. */
public final class PerformanceScreen extends Screen {
    private final Screen parent;
    private final boolean english;
    private final Route route;
    private final long epoch;
    private final Map<String,String> values=new LinkedHashMap<>();
    private final Map<String,TextFieldWidget> inputs=new LinkedHashMap<>();
    private String error="";
    private int column;
    private static final String[][] FIELDS={{"climb_pitch"},{"cruise_altitude","perf_speed"},{"descent_rate","descent_altitude"},{"ils_frequency","approach_speed"}};
    public PerformanceScreen(Screen parent,boolean english,Route route) {
        super(english?Text.literal("PERF"):Ui.t("performance"));
        this.parent=parent;this.english=english;this.route=route;epoch=AiracClient.INSTANCE.chartEpoch;
        if(route!=null) {
            var p=route.performance;
            values.put("climb_pitch",number(p.climbPitch));values.put("cruise_altitude",number(p.cruiseAltitude));
            values.put("perf_speed",number(p.speed));values.put("descent_rate",number(p.descentRate));
            values.put("descent_altitude",number(p.descentAltitude));
            values.put("ils_frequency",RadioNavigation.frequency(Kind.ILS,
                    !p.configured && p.ilsFrequency==0?Performance.DEFAULT_ILS_FREQUENCY:p.ilsFrequency));
            values.put("approach_speed",number(p.approachSpeed));
        }
    }
    private static String number(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private String label(String key) {
        if(!english)return Ui.tr("ui.airac."+key);
        return switch(key) {
            case "climb_pitch"->"TO PITCH / DEG";case "cruise_altitude"->"CRZ ALT / B";
            case "perf_speed"->"TGT SPD / B/S";case "descent_rate"->"DES V/S / B/S";
            case "descent_altitude"->"DES ALT / B";case "ils_frequency"->"ILS FREQ / MHZ";case "approach_speed"->"APPR SPD / B/S";default->key;
        };
    }
    @Override protected void init() {
        if(!dev.airac.compat.FlightAssistantIntegration.available()) {close();return;}
        inputs.clear();column=(width-16)/4;
        for(int i=0;i<FIELDS.length;i++)for(int j=0;j<FIELDS[i].length;j++) {
            String key=FIELDS[i][j];int x=16+i*column,y=82+j*38;
            var input=new TextFieldWidget(textRenderer,x,y,Math.max(30,column-18),18,Text.literal(label(key)));
            input.setMaxLength(16);input.setText(values.getOrDefault(key,""));
            input.setChangedListener(v->values.put(key,v));
            input.setEditable(route!=null && AiracClient.INSTANCE.canEditPlans());
            inputs.put(key,input);addDrawableChild(input);
        }
        addDrawableChild(new FlatButton(width/2-125,height-30,120,20,english?Text.literal("INSERT*"):Ui.t("save"),b->submit())).active=route!=null;
        addDrawableChild(new FlatButton(width/2+5,height-30,120,20,english?Text.literal("<RETURN"):Ui.t("cancel"),b->close()));
    }
    private void submit() {
        if(!dev.airac.compat.FlightAssistantIntegration.available())return;
        var app=AiracClient.INSTANCE;
        try {
            if(epoch!=app.chartEpoch || app.chart.route(route.id)!=route)throw new IllegalArgumentException("sync_conflict");
            if(!app.canEditPlans())throw new IllegalArgumentException("sync_read_only");
            Performance p=new Performance();
            p.climbPitch=Double.parseDouble(values.get("climb_pitch").trim());
            p.cruiseAltitude=Double.parseDouble(values.get("cruise_altitude").trim());
            p.speed=Double.parseDouble(values.get("perf_speed").trim());
            p.approachSpeed=Double.parseDouble(values.get("approach_speed").trim());
            p.descentRate=Double.parseDouble(values.get("descent_rate").trim());
            p.descentAltitude=Double.parseDouble(values.get("descent_altitude").trim());
            p.referenceAltitude=route.performance.referenceAltitude!=null?route.performance.referenceAltitude
                    :client.player!=null?client.player.getY():0;
            String frequency=values.get("ils_frequency").trim();
            p.ilsFrequency=frequency.isEmpty()?0:RadioNavigation.parseFrequency(Kind.ILS,frequency);
            if(dev.airac.nav.RunwayIls.arrival(app.chart,route)==null)p.ilsFrequency=0;
            p.configured=true;p.validate();route.performance=p;app.planChanged();close();
        } catch(NumberFormatException ex){error=english?"INVALID PERF ENTRY":Ui.tr("ui.airac.invalid_performance");}
        catch(IllegalArgumentException ex){error=english?englishError(ex.getMessage()):Ui.tr("ui.airac."+ex.getMessage());}
    }
    private String englishError(String key){return key.equals("invalid_frequency")?"INVALID ILS FREQUENCY":key.equals("sync_conflict")?"F-PLN CHANGED - REOPEN PERF":"CHECK PERF LIMITS";}
    @Override public void render(DrawContext c,int mx,int my,float delta) {
        c.fill(0,0,width,height,Ui.BG);c.drawCenteredTextWithShadow(textRenderer,title,width/2,14,Ui.CYAN);
        String[] headings=english?new String[]{"CLB","CRZ","DES","APPR"}:new String[]{Ui.tr("ui.airac.perf_clb"),Ui.tr("ui.airac.perf_crz"),Ui.tr("ui.airac.perf_des"),Ui.tr("ui.airac.perf_appr")};
        for(int i=0;i<4;i++) {
            int x=8+i*column;
            if(i>0)c.fill(x,42,x+1,height-52,Ui.GRID);
            c.drawCenteredTextWithShadow(textRenderer,Text.literal(headings[i]),x+column/2,48,Ui.CYAN);
            for(int j=0;j<FIELDS[i].length;j++)Ui.scaledFit(c,label(FIELDS[i][j]),x+8,70+j*38,column-18,.75f,Ui.MUTED);
        }
        Ui.fit(c,error,12,height-47,width-24,Ui.AMBER);
        super.render(c,mx,my,delta);
    }
    @Override public void close(){if(client!=null)client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
}
