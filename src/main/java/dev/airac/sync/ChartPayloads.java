package dev.airac.sync;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

public final class ChartPayloads {
    public static final int PROTOCOL = 6;
    public record Request(int protocol, String dimension) implements CustomPayload {
        public static final Id<Request> ID = new Id<>(Identifier.of("airac", "chart_request_v6"));
        public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.of(
            (p,b) -> { b.writeVarInt(p.protocol); b.writeString(p.dimension,128); },
            b -> new Request(b.readVarInt(),b.readString(128)));
        @Override public Id<Request> getId() { return ID; }
    }
    public record Upload(String dimension, long revision, UUID transfer, int index, int total, byte[] bytes) implements CustomPayload {
        public static final Id<Upload> ID = new Id<>(Identifier.of("airac", "chart_upload_v6"));
        public static final PacketCodec<RegistryByteBuf, Upload> CODEC = PacketCodec.of(
            (p,b) -> { b.writeString(p.dimension,128); b.writeLong(p.revision); b.writeUuid(p.transfer);
                b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.bytes); },
            b -> new Upload(b.readString(128),b.readLong(),b.readUuid(),b.readVarInt(),b.readVarInt(),b.readByteArray(ChunkAssembly.CHUNK_BYTES)));
        @Override public Id<Upload> getId() { return ID; }
    }
    public record Snapshot(String serverId, String dimension, long revision, boolean editable, String status,
                           UUID transfer, int index, int total, byte[] bytes) implements CustomPayload {
        public static final Id<Snapshot> ID = new Id<>(Identifier.of("airac", "chart_snapshot_v6"));
        public static final PacketCodec<RegistryByteBuf, Snapshot> CODEC = PacketCodec.of(
            (p,b) -> { b.writeString(p.serverId,80); b.writeString(p.dimension,128); b.writeLong(p.revision);
                b.writeBoolean(p.editable); b.writeString(p.status,80); b.writeUuid(p.transfer);
                b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.bytes); },
            b -> new Snapshot(b.readString(80),b.readString(128),b.readLong(),b.readBoolean(),b.readString(80),
                b.readUuid(),b.readVarInt(),b.readVarInt(),b.readByteArray(ChunkAssembly.CHUNK_BYTES)));
        @Override public Id<Snapshot> getId() { return ID; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.ID,Request.CODEC);
        PayloadTypeRegistry.playC2S().register(Upload.ID,Upload.CODEC);
        PayloadTypeRegistry.playS2C().register(Snapshot.ID,Snapshot.CODEC);
    }
    private ChartPayloads() {}
}
