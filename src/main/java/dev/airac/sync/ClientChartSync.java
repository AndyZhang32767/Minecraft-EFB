package dev.airac.sync;

import dev.airac.AiracClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import java.util.Arrays;
import java.util.UUID;

/** Capability discovery is optional; no login requirement or mandatory client handshake. */
public final class ClientChartSync {
    private final AiracClient app;
    private final ChunkAssembly assembly = new ChunkAssembly();
    public boolean shared, waiting, pending, editable;
    private String requestedDimension = "", serverId = "";
    private long revision, requestedAt, pendingAt;
    private byte[] canonical;
    private ChartPayloads.Snapshot first;
    public ClientChartSync(AiracClient app) { this.app = app; }
    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(ChartPayloads.Snapshot.ID,(payload,context) -> {
            app.syncWorld(context.client());
            receive(payload);
        });
    }
    public void reset() {
        shared = waiting = pending = editable = false;
        requestedDimension = serverId = ""; canonical = null; first = null;
        requestedAt = pendingAt = revision = 0; assembly.reset();
    }
    public void tick(MinecraftClient client) {
        if(client.world==null || client.player==null)return;
        // Keep existing singleplayer charts local. LAN guests use the optional server protocol.
        if(client.getServer()!=null && !client.getServer().isRemote())return;
        if(!ClientPlayNetworking.canSend(ChartPayloads.Request.ID))return;
        long now=System.currentTimeMillis();
        if(pending && now-pendingAt>10_000){pending=false;waiting=true;requestedAt=0;}
        if(!app.dimension.equals(requestedDimension) || (!shared || waiting) && now-requestedAt>2000)request();
    }
    private void request() {
        requestedDimension=app.dimension;requestedAt=System.currentTimeMillis();waiting=true;
        ClientPlayNetworking.send(new ChartPayloads.Request(ChartPayloads.PROTOCOL,app.dimension));
    }
    public boolean canEdit() { return !waiting && !pending && (!shared || editable); }
    public void publish() {
        if(!shared || pending)return;
        byte[] bytes;
        try { bytes=SharedCharts.encode(app.chart); }
        catch(RuntimeException e){app.syncError("invalid");restore();return;}
        if(Arrays.equals(bytes,canonical))return;
        if(!canEdit() || !ClientPlayNetworking.canSend(ChartPayloads.Upload.ID)) {restore();return;}
        pending=true;pendingAt=System.currentTimeMillis();
        UUID transfer=UUID.randomUUID();int count=(bytes.length+ChunkAssembly.CHUNK_BYTES-1)/ChunkAssembly.CHUNK_BYTES;
        for(int i=0;i<count;i++)ClientPlayNetworking.send(new ChartPayloads.Upload(app.dimension,revision,transfer,i,count,
            Arrays.copyOfRange(bytes,i*ChunkAssembly.CHUNK_BYTES,Math.min(bytes.length,(i+1)*ChunkAssembly.CHUNK_BYTES))));
    }
    private void restore() { if(canonical!=null)SharedCharts.apply(app.chart,SharedCharts.decode(canonical)); }
    private void receive(ChartPayloads.Snapshot payload) {
        var client=MinecraftClient.getInstance();
        if(client.getServer()!=null && !client.getServer().isRemote())return;
        if(!payload.dimension().equals(app.dimension) || !ClientPlayNetworking.canSend(ChartPayloads.Request.ID))return;
        try {
            if(payload.index()==0)first=payload;
            if(first==null || !first.transfer().equals(payload.transfer()) || first.revision()!=payload.revision()
                || first.editable()!=payload.editable() || !first.serverId().equals(payload.serverId())
                || !first.status().equals(payload.status()))throw new IllegalArgumentException();
            byte[] bytes=assembly.accept(payload.transfer(),payload.index(),payload.total(),payload.bytes(),System.currentTimeMillis());
            if(bytes==null)return;
            var data=SharedCharts.decode(bytes);
            if(shared && serverId.equals(payload.serverId()) && payload.revision()<revision)return;
            boolean switched=!shared || !serverId.equals(payload.serverId());
            if(switched)app.useServerChart(payload.serverId());
            // A failed server read has no authoritative replacement; preserve cached personal plans.
            boolean unavailable=payload.status().equals("read_only");
            if(!unavailable)SharedCharts.apply(app.chart,data);
            shared=true;waiting=pending=false;editable=payload.editable();serverId=payload.serverId();
            revision=payload.revision();canonical=unavailable?SharedCharts.encode(app.chart):bytes;requestedDimension=app.dimension;
            app.syncError(payload.status());app.sharedChartUpdated();app.save();
        } catch(RuntimeException e) {
            assembly.reset();first=null;editable=false;waiting=true;requestedAt=System.currentTimeMillis();
            app.syncError("invalid");AiracClient.LOGGER.error("Invalid EFB server snapshot",e);
        }
    }
    public String statusKey() {
        if(pending)return "sync_saving";
        if(waiting)return "sync_waiting";
        if(shared)return editable?"sync_operator":"sync_viewer";
        return "sync_local";
    }
}
