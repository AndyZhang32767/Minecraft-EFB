package dev.airac.map;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.airac.AiracClient;
import dev.airac.ui.Ui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;

import java.lang.reflect.Method;
import java.util.*;

/** Read-only view of Xaero's existing textures. Never schedules loads or changes Xaero state. */
public final class XaeroMapRenderer {
    // Xaero stores lighting in alpha, so ordinary translucent GUI drawing loses terrain.
    private static final RenderPipeline MAP = RenderPipeline.builder()
            .withLocation(Identifier.of("airac","pipeline/xaero_map"))
            .withVertexShader("core/position_tex_color").withFragmentShader(Identifier.of("airac","core/xaero_map"))
            .withSampler("Sampler0").withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false).build();
    private final Map<String, Method> methods = new HashMap<>();
    private Object session;
    private boolean failed;
    private int renderedTextures;
    public int renderedTextures() { return renderedTextures; }
    public void reset() { session=null; renderedTextures=0; }

    private Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        String key = type.getName()+"#"+name+"/"+args.length;
        for(Object arg:args)key+="/"+(arg==null?"null":arg.getClass().getName());
        Method method=methods.get(key);
        if(method==null) {
            outer: for(Method candidate:type.getMethods()) {
                if(!candidate.getName().equals(name) || candidate.getParameterCount()!=args.length) continue;
                Class<?>[] types=candidate.getParameterTypes();
                for(int i=0;i<args.length;i++) {
                    Class<?> t=types[i];
                    if(t==int.class)t=Integer.class; else if(t==boolean.class)t=Boolean.class;
                    if(args[i]!=null && !t.isInstance(args[i]))continue outer;
                }
                method=candidate;break;
            }
            if(method==null)throw new NoSuchMethodException(key);
            methods.put(key,method);
        }
        return method.invoke(target instanceof Class<?> ? null : target,args);
    }
    private boolean yes(Object target,String name,Object... args) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(target,name,args));
    }

    public void draw(DrawContext c,int left,int top,int width,int height,double cx,double cz,double scale) {
        renderedTextures=0;
        String status="";
        if(!FabricLoader.getInstance().isModLoaded("xaeroworldmap"))status="xaero_missing";
        else if(failed)status="xaero_error";
        else try {
            Object current=call(Class.forName("xaero.map.WorldMapSession"),"getCurrentSession");
            if(current!=session){reset();session=current;}
            if(current==null || !yes(current,"isUsable"))status="xaero_waiting";
            else {
                Object processor=call(current,"getMapProcessor");
                // A world switch may leave the old map session alive for several frames.
                if(!yes(processor,"isMapWorldUsable") || yes(processor,"isWaitingForWorldUpdate")
                        || call(processor,"getWorld")!=MinecraftClient.getInstance().world)status="xaero_waiting";
                else synchronized(processor.getClass().getField("uiSync").get(processor)) {
                    renderVisible(c,processor,left,top,width,height,cx,cz,scale);
                    if(renderedTextures==0)status="xaero_empty";
                }
            }
        } catch(ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed=true;status="xaero_error";
            AiracClient.LOGGER.error("Xaero World Map bridge unavailable; no replacement terrain will be generated",e);
        }
        if(!status.isEmpty())Ui.fit(c,Ui.tr("ui.airac."+status),left+10,top+10,width-20,Ui.MUTED);
    }

    private record Region(Object value,int x,int z,int level) {}
    private int number(Object object,String name) throws ReflectiveOperationException {
        return ((Number)call(object,name)).intValue();
    }
    private void renderVisible(DrawContext c,Object p,int left,int top,int width,int height,
                               double cx,double cz,double scale) throws ReflectiveOperationException {
        int layer=number(p,"getCurrentCaveLayer");
        Object dimension=call(call(p,"getMapWorld"),"getCurrentDimension");
        Object manager=call(dimension,"getLayeredMapRegions");
        // Even getLeveledRegion can create a map layer. Snapshot only the already loaded list.
        List<?> loaded=(List<?>)call(manager,"getLoadedListUnsynced");
        List<?> snapshot;
        synchronized(loaded){snapshot=new ArrayList<>(loaded);}
        List<Region> regions=new ArrayList<>();
        for(Object region:snapshot) {
            if(call(region,"getDim")!=dimension || number(region,"getCaveLayer")!=layer)continue;
            int level=number(region,"getLevel");
            if(level<0 || level>3)continue;
            regions.add(new Region(region,number(region,"getRegionX"),number(region,"getRegionZ"),level));
        }
        // Overview first, detailed source tiles last. A missing overview never hides loaded detail.
        regions.sort(Comparator.comparingInt(Region::level).reversed());
        int lastLevel=-1;
        for(Region item:regions) {
            if(item.level!=lastLevel){c.state.createNewRootLayer();lastLevel=item.level;}
            Object region=item.value;
            synchronized(region) {
                if(!yes(region,"hasTextures"))continue;
                int tileSize=64<<item.level;
                double originX=(double)item.x*(512<<item.level),originZ=(double)item.z*(512<<item.level);
                for(int z=0;z<8;z++)for(int x=0;x<8;x++) {
                    int x1=(int)Math.round(left+width/2.0+(originX+x*tileSize-cx)*scale);
                    int y1=(int)Math.round(top+height/2.0+(originZ+z*tileSize-cz)*scale);
                    int x2=(int)Math.round(left+width/2.0+(originX+(x+1)*tileSize-cx)*scale);
                    int y2=(int)Math.round(top+height/2.0+(originZ+(z+1)*tileSize-cz)*scale);
                    if(x2<=left || y2<=top || x1>=left+width || y1>=top+height || x2<=x1 || y2<=y1)continue;
                    Object texture=call(region,"getTexture",x,z);
                    if(texture==null || call(texture,"getRegion")!=region)continue;
                    Object gpu=call(texture,"getGlColorTexture");
                    if(gpu==null)continue;
                    var view=(GpuTextureView)gpu.getClass().getField("view").get(gpu);
                    if(view==null || view.isClosed())continue;
                    c.state.addSimpleElement(new TexturedQuadGuiElementRenderState(MAP,
                            TextureSetup.of(view,RenderSystem.getSamplerCache().get(FilterMode.NEAREST)),
                            new Matrix3x2f(c.getMatrices()),x1,y1,x2,y2,0,1,0,1,-1,c.scissorStack.peekLast()));
                    renderedTextures++;
                }
            }
        }
        c.state.createNewRootLayer();
    }
}
