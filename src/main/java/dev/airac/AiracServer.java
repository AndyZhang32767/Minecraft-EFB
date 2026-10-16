package dev.airac;

import dev.airac.sync.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import java.util.*;
import java.io.IOException;
import org.slf4j.LoggerFactory;

public final class AiracServer implements ModInitializer {
    private static final Map<MinecraftServer,Session> SESSIONS = new IdentityHashMap<>();
    private static final class Subscription {
        String dimension;
        boolean operator;
        long requestAt, editAt, rejectedAt;
        long uploadRevision;
        UUID uploadId;
        final ChunkAssembly assembly = new ChunkAssembly();
    }
    private static final class Session {
        SharedChartRepository repository;
        final Map<UUID,Subscription> clients = new HashMap<>();
    }
    @Override public void onInitialize() {
        ChartPayloads.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var session = new Session();
            try { session.repository = new SharedChartRepository(server.getSavePath(WorldSavePath.ROOT).resolve("efb")); }
            catch (IOException | RuntimeException e) { LoggerFactory.getLogger("airac-server").error("EFB shared storage unavailable",e); }
            SESSIONS.put(server,session);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(SESSIONS::remove);
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> {
            var session=SESSIONS.get(server);if(session!=null)session.clients.remove(handler.player.getUuid());
        });
        ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.Request.ID,(p,c) -> request(c.server(),c.player(),p));
        ServerPlayNetworking.registerGlobalReceiver(ChartPayloads.Upload.ID,(p,c) -> upload(c.server(),c.player(),p));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if(server.getTicks()%20!=0)return;
            var session=SESSIONS.get(server);if(session==null)return;
            for(var player:server.getPlayerManager().getPlayerList()) {
                var sub=session.clients.get(player.getUuid());if(sub==null)continue;
                String dimension=dimension(player);boolean operator=operator(server,player);
                if(!dimension.equals(sub.dimension) || operator!=sub.operator) {
                    sub.assembly.reset();sub.dimension=dimension;sub.operator=operator;send(session,player,sub,"");
                }
            }
        });
    }
    private static String dimension(ServerPlayerEntity player) { return player.getEntityWorld().getRegistryKey().getValue().toString(); }
    private static boolean operator(MinecraftServer server,ServerPlayerEntity player) {
        return server.getPlayerManager().isOperator(new PlayerConfigEntry(player.getGameProfile()));
    }
    private static void request(MinecraftServer server,ServerPlayerEntity player,ChartPayloads.Request payload) {
        if(payload.protocol()!=ChartPayloads.PROTOCOL || !ServerPlayNetworking.canSend(player.networkHandler,ChartPayloads.Snapshot.ID))return;
        if(!dimension(player).equals(payload.dimension()))return;
        var session=SESSIONS.get(server);if(session==null)return;
        var sub=session.clients.computeIfAbsent(player.getUuid(),id -> new Subscription());
        long now=System.currentTimeMillis();if(now-sub.requestAt<500)return;sub.requestAt=now;
        sub.dimension=dimension(player);sub.operator=operator(server,player);sub.assembly.reset();send(session,player,sub,"");
    }
    private static void upload(MinecraftServer server,ServerPlayerEntity player,ChartPayloads.Upload payload) {
        var session=SESSIONS.get(server);if(session==null)return;
        var sub=session.clients.get(player.getUuid());if(sub==null)return;
        if(!dimension(player).equals(payload.dimension()) || !payload.dimension().equals(sub.dimension))return;
        sub.operator=operator(server,player);
        if(!sub.operator){sub.assembly.reset();if(payload.index()==0)reject(session,player,sub,"forbidden");return;}
        if(session.repository==null){if(payload.index()==0)reject(session,player,sub,"read_only");return;}
        long now=System.currentTimeMillis();
        try {
            if(payload.index()==0) {
                if(now-sub.editAt<100){reject(session,player,sub,"busy");return;}
                sub.editAt=now;sub.uploadRevision=payload.revision();sub.uploadId=payload.transfer();
            }
            if(payload.revision()!=sub.uploadRevision || !payload.transfer().equals(sub.uploadId))throw new IllegalArgumentException();
            byte[] bytes=sub.assembly.accept(payload.transfer(),payload.index(),payload.total(),payload.bytes(),now);
            if(bytes==null)return;
            var result=session.repository.update(sub.dimension,payload.revision(),bytes,sub.operator);
            if(result!=SharedChartRepository.Result.OK){reject(session,player,sub,result.name().toLowerCase(Locale.ROOT));return;}
            for(var recipient:server.getPlayerManager().getPlayerList()) {
                var other=session.clients.get(recipient.getUuid());
                if(other!=null && sub.dimension.equals(other.dimension) && sub.dimension.equals(dimension(recipient))) {
                    other.operator=operator(server,recipient);send(session,recipient,other,"");
                }
            }
        } catch(RuntimeException e) { sub.assembly.reset();reject(session,player,sub,"invalid"); }
    }
    private static void reject(Session session,ServerPlayerEntity player,Subscription sub,String status) {
        long now=System.currentTimeMillis();
        if(now-sub.rejectedAt<500)return;
        sub.rejectedAt=now;send(session,player,sub,status);
    }
    private static void send(Session session,ServerPlayerEntity player,Subscription sub,String status) {
        // Unmodified clients receive no EFB packets and are never required to negotiate.
        if(!ServerPlayNetworking.canSend(player.networkHandler,ChartPayloads.Snapshot.ID))return;
        var entry=session.repository==null?new SharedChartRepository.Entry():session.repository.get(sub.dimension);
        String id=session.repository==null?"unavailable":session.repository.serverId;
        if(session.repository==null || !entry.writable)status="read_only";
        byte[] bytes=entry.bytes;int count=Math.max(1,(bytes.length+ChunkAssembly.CHUNK_BYTES-1)/ChunkAssembly.CHUNK_BYTES);
        UUID transfer=UUID.randomUUID();
        for(int i=0;i<count;i++)ServerPlayNetworking.send(player,new ChartPayloads.Snapshot(id,sub.dimension,entry.revision,
            sub.operator && entry.writable && session.repository!=null,status,transfer,i,count,
            Arrays.copyOfRange(bytes,i*ChunkAssembly.CHUNK_BYTES,Math.min(bytes.length,(i+1)*ChunkAssembly.CHUNK_BYTES))));
    }
}
