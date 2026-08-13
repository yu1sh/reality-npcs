package io.github.yu1sh.reality.npcs;

import java.util.function.Supplier;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Network adapter for the server-owned administrator menu contract. */
final class NpcNetwork {
    private static final int MAX_STABLE_ID_LENGTH = 80;
    private static final int MAX_ENTRIES = 64;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RealityNpcsMod.MOD_ID, "main"),
            () -> "1",
            "1"::equals,
            "1"::equals);

    private NpcNetwork() {
    }

    static void register() {
        CHANNEL.registerMessage(
                0,
                Request.class,
                Request::encode,
                Request::decode,
                Request::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(
                1,
                Snapshot.class,
                Snapshot::encode,
                Snapshot::decode,
                Snapshot::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void sendToServer(Request request) {
        CHANNEL.sendToServer(request);
    }

    static void sendSnapshot(ServerPlayer player, NpcAdminSnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(snapshot));
    }

    static final class Request {
        private final long sessionId;
        private final long requestId;
        private final long snapshotRevision;
        private final NpcAdminOperation operation;
        private final String stableId;

        Request(
                long sessionId,
                long requestId,
                long snapshotRevision,
                NpcAdminOperation operation,
                String stableId) {
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.snapshotRevision = snapshotRevision;
            this.operation = operation;
            this.stableId = stableId == null ? "" : stableId;
        }

        private static void encode(Request request, FriendlyByteBuf buffer) {
            buffer.writeLong(request.sessionId);
            buffer.writeLong(request.requestId);
            buffer.writeLong(request.snapshotRevision);
            buffer.writeByte(request.operation == null ? -1 : request.operation.ordinal());
            buffer.writeUtf(request.stableId, MAX_STABLE_ID_LENGTH);
        }

        private static Request decode(FriendlyByteBuf buffer) {
            long sessionId = buffer.readLong();
            long requestId = buffer.readLong();
            long snapshotRevision = buffer.readLong();
            int operationOrdinal = buffer.readByte();
            NpcAdminOperation operation = operationOrdinal >= 0
                    && operationOrdinal < NpcAdminOperation.values().length
                    ? NpcAdminOperation.values()[operationOrdinal]
                    : null;
            String stableId = buffer.readUtf(MAX_STABLE_ID_LENGTH);
            return new Request(sessionId, requestId, snapshotRevision, operation, stableId);
        }

        private static void handle(Request request, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (context.getDirection().getReceptionSide().isClient()) {
                context.setPacketHandled(true);
                return;
            }
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender != null) {
                    NpcAdminController.handleRequest(
                            sender,
                            request.sessionId,
                            request.requestId,
                            request.snapshotRevision,
                            request.operation,
                            request.stableId);
                }
            });
            context.setPacketHandled(true);
        }
    }

    private static final class Snapshot {
        private final NpcAdminSnapshot snapshot;

        private Snapshot(NpcAdminSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        private static void encode(Snapshot packet, FriendlyByteBuf buffer) {
            NpcAdminSnapshot value = packet.snapshot;
            buffer.writeLong(value.sessionId());
            buffer.writeLong(value.revision());
            buffer.writeVarInt(value.entries().size());
            for (NpcAdminSnapshot.Entry entry : value.entries()) {
                buffer.writeUtf(entry.stableId(), MAX_STABLE_ID_LENGTH);
                buffer.writeBoolean(entry.enabled());
                buffer.writeUtf(entry.dimension(), 128);
                buffer.writeInt(entry.anchorX());
                buffer.writeInt(entry.anchorY());
                buffer.writeInt(entry.anchorZ());
                buffer.writeUtf(entry.entityUuid(), 64);
                buffer.writeBoolean(entry.entityPresent());
            }
        }

        private static Snapshot decode(FriendlyByteBuf buffer) {
            long sessionId = buffer.readLong();
            long revision = buffer.readLong();
            int entryCount = buffer.readVarInt();
            if (entryCount < 0 || entryCount > MAX_ENTRIES) {
                throw new IllegalArgumentException("invalid reality-npcs snapshot entry count");
            }
            java.util.ArrayList<NpcAdminSnapshot.Entry> entries = new java.util.ArrayList<>(entryCount);
            for (int index = 0; index < entryCount; index++) {
                entries.add(new NpcAdminSnapshot.Entry(
                        buffer.readUtf(MAX_STABLE_ID_LENGTH),
                        buffer.readBoolean(),
                        buffer.readUtf(128),
                        buffer.readInt(),
                        buffer.readInt(),
                        buffer.readInt(),
                        buffer.readUtf(64),
                        buffer.readBoolean()));
            }
            return new Snapshot(new NpcAdminSnapshot(sessionId, revision, entries));
        }

        private static void handle(Snapshot packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (!context.getDirection().getReceptionSide().isClient()) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> NpcClient.receiveSnapshot(packet.snapshot)));
            context.setPacketHandled(true);
        }
    }
}
