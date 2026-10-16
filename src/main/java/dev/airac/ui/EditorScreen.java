package dev.airac.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.Consumer;

public final class EditorScreen extends Screen {
    public record Field(String key, String value, List<String> choices) {
        public Field(String key, String value) { this(key, value, List.of()); }
    }
    private Screen parent;
    private final boolean mcdu;
    private net.minecraft.text.Text label(String key) { return mcdu?McduText.t(key):Ui.t(key); }
    public EditorScreen returnTo(Screen screen) { parent=screen; return this; }
    private final List<Field> fields;
    private final Consumer<Map<String,String>> save;
    private final Map<String,TextFieldWidget> inputs=new LinkedHashMap<>();
    private final Map<String,String> selections=new HashMap<>();
    private final Map<String,String> drafts=new HashMap<>();
    private final Set<String> ilsFields=new HashSet<>();
    private List<Field> visibleFields=List.of();
    public EditorScreen ilsOptions(String... keys) { ilsFields.addAll(Arrays.asList(keys));clearAndInit();return this; }
    private Runnable afterSave=()->{};
    public EditorScreen afterSave(Runnable action) { afterSave=action; return this; }
    private String error="";
    private int left,top,columns,columnWidth;
    public EditorScreen(Screen parent,String title,List<Field> fields,Consumer<Map<String,String>> save) {
        super(parent instanceof ChartScreen chart && chart.fpln?McduText.t(title):Ui.t(title));this.parent=parent;this.fields=fields;this.save=save;
        this.mcdu=parent instanceof ChartScreen chart && chart.fpln;
    }
    @Override protected void init() {
        inputs.forEach((k,v)->drafts.put(k,v.getText())); inputs.clear();
        visibleFields=fields.stream().filter(f->!ilsFields.contains(f.key()) || selections.getOrDefault("ils_enabled","off").equals("on")).toList();
        columns=visibleFields.size()>6?2:1; columnWidth=Math.min(180,(width-40)/columns);
        left=(width-columnWidth*columns)/2;
        int rows=(visibleFields.size()+columns-1)/columns;
        top=Math.max(26,(height-(rows*29+40))/2);
        for(int i=0;i<visibleFields.size();i++) {
            var field=visibleFields.get(i);
            int x=left+(i%columns)*columnWidth, y=top+(i/columns)*29;
            if(!field.choices().isEmpty()) {
                selections.putIfAbsent(field.key(),field.value());
                addDrawableChild(new FlatButton(x,y+10,columnWidth-10,17,
                        label(selections.get(field.key())),b->{
                    int next=(field.choices().indexOf(selections.get(field.key()))+1)%field.choices().size();
                    selections.put(field.key(),field.choices().get(next));b.setMessage(label(selections.get(field.key())));
                    if(field.key().equals("ils_enabled"))clearAndInit();
                }));
                continue;
            }
            var input=new TextFieldWidget(textRenderer,x,y+10,columnWidth-10,17,label(field.key()));
            input.setMaxLength((field.key().equals("fixes") || field.key().equals("sid_fixes"))?16384:field.key().equals("note")?240:80);
            input.setText(drafts.getOrDefault(field.key(),field.value()));
            inputs.put(field.key(),input);addDrawableChild(input);
        }
        int buttonsY=top+rows*29+18;
        addDrawableChild(new FlatButton(left,buttonsY,columnWidth*columns/2-5,20,label("save"),b->submit()));
        addDrawableChild(new FlatButton(left+columnWidth*columns/2,buttonsY,columnWidth*columns/2-10,20,label("cancel"),b->close()));
        if(!inputs.isEmpty()) setInitialFocus(inputs.values().iterator().next());
    }
    private void submit() {
        Map<String,String> values=new LinkedHashMap<>();fields.forEach(f->values.put(f.key(),f.value()));
        values.putAll(drafts);values.putAll(selections);inputs.forEach((k,v)->values.put(k,v.getText().trim()));
        try { save.accept(values);close();afterSave.run(); } catch(IllegalArgumentException e) { error=e.getMessage(); }
    }
    @Override public void render(DrawContext c,int mx,int my,float delta) {
        c.fill(0,0,width,height,0xff0b141d); c.drawCenteredTextWithShadow(textRenderer,title,width/2,12,Ui.CYAN);
        for(int i=0;i<visibleFields.size();i++) Ui.text(c,label(visibleFields.get(i).key()).getString(),left+(i%columns)*columnWidth,top+(i/columns)*29,Ui.MUTED);
        Ui.fit(c,error,left,top+((visibleFields.size()+columns-1)/columns)*29+5,columnWidth*columns,Ui.AMBER);
        super.render(c,mx,my,delta);
    }
    @Override public boolean keyPressed(KeyInput input) {
        if(input.key()==GLFW.GLFW_KEY_ENTER || input.key()==GLFW.GLFW_KEY_KP_ENTER) { submit();return true; }
        return super.keyPressed(input);
    }
    @Override public void close() { if(client!=null) client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
    public static double number(Map<String,String> values,String key) {
        try {
            double result=Double.parseDouble(values.get(key));
            if(!Double.isFinite(result) || Math.abs(result)>30_000_000) throw new NumberFormatException();
            return result;
        } catch(RuntimeException e) { throw new IllegalArgumentException(Ui.tr("ui.airac.invalid_number",Ui.tr("ui.airac."+key))); }
    }
    public static String required(Map<String,String> values,String key) {
        String result=values.get(key);
        if(result==null || result.isBlank()) throw new IllegalArgumentException(Ui.tr("ui.airac.required",Ui.tr("ui.airac."+key)));
        return result;
    }
}
