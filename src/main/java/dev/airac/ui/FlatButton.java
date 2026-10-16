package dev.airac.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Flat chart controls with vanilla keyboard focus and narration support. */
public final class FlatButton extends ButtonWidget {
    private int textColor=-1;
    public FlatButton(int x,int y,int width,int height,net.minecraft.text.Text text,PressAction action) {
        super(x,y,width,height,text,action,DEFAULT_NARRATION_SUPPLIER);
        setTooltip(Tooltip.of(text));
    }
    public FlatButton color(int color){textColor=color;return this;}
    @Override protected void drawIcon(DrawContext c,int mouseX,int mouseY,float delta) {
        boolean hover=isHovered() || isFocused();
        int background=!active?0xff14202a:hover?0xff294952:0xff21333f;
        int border=hover && active?Ui.CYAN:0xff38515e;
        c.fill(getX(),getY(),getX()+width,getY()+height,background);
        c.fill(getX(),getY()+height-1,getX()+width,getY()+height,border);
        var font=MinecraftClient.getInstance().textRenderer;
        String label=font.trimToWidth(getMessage().getString(),Math.max(1,width-8));
        Ui.text(c,label,getX()+(width-font.getWidth(label))/2,getY()+(height-8)/2,textColor>=0?textColor:active?Ui.TEXT:Ui.MUTED);
    }
}
