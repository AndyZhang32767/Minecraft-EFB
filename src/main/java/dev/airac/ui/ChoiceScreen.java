package dev.airac.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import java.util.*;
import java.util.function.Consumer;

/** A paginated selector; cancel never commits a partial runway/procedure selection. */
public final class ChoiceScreen extends Screen {
    public record Choice(String id, String label) {}
    private final Screen parent;
    private net.minecraft.text.Text label(String key) { return parent instanceof ChartScreen chart && chart.fpln?McduText.t(key):Ui.t(key); }
    private final List<Choice> choices;
    private final Consumer<String> select;
    private final String subtitle;
    private int page, rows, left, panelWidth;
    private String error = "";
    public ChoiceScreen(Screen parent, String title, String subtitle, List<Choice> choices, Consumer<String> select) {
        super(parent instanceof ChartScreen chart && chart.fpln?McduText.t(title):Ui.t(title)); this.parent=parent; this.subtitle=subtitle; this.choices=choices; this.select=select;
    }
    @Override protected void init() {
        panelWidth=Math.min(380,width-24);left=(width-panelWidth)/2;
        rows=Math.max(1,(height-108)/23);page=Math.clamp(page,0,Math.max(0,(choices.size()-1)/rows));
        for(int i=page*rows;i<Math.min(choices.size(),(page+1)*rows);i++) {
            Choice choice=choices.get(i);
            addDrawableChild(new FlatButton(left,54+(i-page*rows)*23,panelWidth,20,
                    net.minecraft.text.Text.literal(choice.label()), b->{
                try { select.accept(choice.id()); } catch(IllegalArgumentException ex) { error=ex.getMessage(); }
            }));
        }
        addDrawableChild(new FlatButton(left,height-29,50,20,label("previous"),b->{page--;clearAndInit();})).active=page>0;
        addDrawableChild(new FlatButton(left+54,height-29,50,20,label("next"),b->{page++;clearAndInit();})).active=(page+1)*rows<choices.size();
        addDrawableChild(new FlatButton(left+panelWidth-100,height-29,100,20,label("cancel"),b->close()));
    }
    @Override public void render(DrawContext c,int mx,int my,float delta) {
        c.fill(0,0,width,height,Ui.BG);
        c.drawCenteredTextWithShadow(textRenderer,title,width/2,14,Ui.CYAN);
        Ui.fit(c,subtitle,left,34,panelWidth,Ui.MUTED);
        Ui.fit(c,error,left,height-44,panelWidth,Ui.AMBER);
        super.render(c,mx,my,delta);
    }
    @Override public boolean mouseScrolled(double x,double y,double h,double v) {
        page-=(int)Math.signum(v);clearAndInit();return true;
    }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
