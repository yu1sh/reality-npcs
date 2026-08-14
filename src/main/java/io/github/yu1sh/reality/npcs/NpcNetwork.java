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
    static final int MAX_DIMENSION_LENGTH = 128;
    static final int MAX_GUIDE_TEXT_LENGTH = NpcManager.MAX_GUIDE_TEXT_LENGTH;
    private static final int MAX_ENTRIES = 64;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RealityNpcsMod.MOD_ID, "main"),
            () -> "3",
            "3"::equals,
            "3"::equals);

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
        CHANNEL.registerMessage(
                2,
                GuideTextRequest.class,
                GuideTextRequest::encode,
                GuideTextRequest::decode,
                GuideTextRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(
                3,
                GuideSnapshotPacket.class,
                GuideSnapshotPacket::encode,
                GuideSnapshotPacket::decode,
                GuideSnapshotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void sendToServer(Request request) {
        CHANNEL.sendToServer(request);
    }

    static void sendSnapshot(ServerPlayer player, NpcAdminSnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(snapshot));
    }

    static void sendGuideTextToServer(GuideTextRequest request) {
        CHANNEL.sendToServer(request);
    }

    static void sendGuideSnapshot(ServerPlayer player, NpcGuideSnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new GuideSnapshotPacket(snapshot));
    }

    static final class Request {
        private final long sessionId;
        private final long requestId;
        private final long snapshotRevision;
        private final NpcAdminOperation operation;
        private final String stableId;
        private final String dimension;
        private final int x;
        private final int y;
        private final int z;

        Request(
                long sessionId,
                long requestId,
                long snapshotRevision,
                NpcAdminOperation operation,
                String stableId) {
            this(sessionId, requestId, snapshotRevision, operation, stableId, "", 0, 0, 0);
        }

        Request(
                long sessionId,
                long requestId,
                long snapshotRevision,
                NpcAdminOperation operation,
                String stableId,
                String dimension,
                int x,
                int y,
                int z) {
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.snapshotRevision = snapshotRevision;
            this.operation = operation;
            this.stableId = stableId == null ? "" : stableId;
            this.dimension = dimension == null ? "" : dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static void encode(Request request, FriendlyByteBuf buffer) {
            buffer.writeLong(request.sessionId);
            buffer.writeLong(request.requestId);
            buffer.writeLong(request.snapshotRevision);
            buffer.writeByte(request.operation == null ? -1 : request.operation.ordinal());
            buffer.writeUtf(request.stableId, MAX_STABLE_ID_LENGTH);
            buffer.writeUtf(request.dimension, MAX_DIMENSION_LENGTH);
            buffer.writeInt(request.x);
            buffer.writeInt(request.y);
            buffer.writeInt(request.z);
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
            String dimension = buffer.readUtf(MAX_DIMENSION_LENGTH);
            int x = buffer.readInt();
            int y = buffer.readInt();
            int z = buffer.readInt();
            return new Request(sessionId, requestId, snapshotRevision, operation, stableId, dimension, x, y, z);
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
                            request.stableId,
                            request.dimension,
                            request.x,
                            request.y,
                            request.z);
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
                buffer.writeUtf(entry.guideText(), MAX_GUIDE_TEXT_LENGTH);
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
                        buffer.readBoolean(),
                        buffer.readUtf(MAX_GUIDE_TEXT_LENGTH)));
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

    static final class GuideTextRequest {
        private final long sessionId;
        private final long requestId;
        private final long snapshotRevision;
        private final String stableId;
        private final String guideText;

        GuideTextRequest(
                long sessionId,
                long requestId,
                long snapshotRevision,
                String stableId,
                String guideText) {
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.snapshotRevision = snapshotRevision;
            this.stableId = stableId == null ? "" : stableId;
            this.guideText = guideText == null ? "" : guideText;
        }

        private static void encode(GuideTextRequest request, FriendlyByteBuf buffer) {
            buffer.writeLong(request.sessionId);
            buffer.writeLong(request.requestId);
            buffer.writeLong(request.snapshotRevision);
            buffer.writeUtf(request.stableId, MAX_STABLE_ID_LENGTH);
            buffer.writeUtf(request.guideText, MAX_GUIDE_TEXT_LENGTH);
        }

        private static GuideTextRequest decode(FriendlyByteBuf buffer) {
            return new GuideTextRequest(
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readUtf(MAX_STABLE_ID_LENGTH),
                    buffer.readUtf(MAX_GUIDE_TEXT_LENGTH));
        }

        private static void handle(
                GuideTextRequest request,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (context.getDirection().getReceptionSide().isClient()) {
                context.setPacketHandled(true);
                return;
            }
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender != null) {
                    NpcAdminController.handleGuideTextRequest(
                            sender,
                            request.sessionId,
                            request.requestId,
                            request.snapshotRevision,
                            request.stableId,
                            request.guideText);
                }
            });
            context.setPacketHandled(true);
        }
    }

    private static final class GuideSnapshotPacket {
        private final NpcGuideSnapshot snapshot;

        private GuideSnapshotPacket(NpcGuideSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        private static void encode(GuideSnapshotPacket packet, FriendlyByteBuf buffer) {
            buffer.writeLong(packet.snapshot.sessionId());
            buffer.writeComponent(packet.snapshot.title());
            buffer.writeComponent(packet.snapshot.body());
        }

        private static GuideSnapshotPacket decode(FriendlyByteBuf buffer) {
            return new GuideSnapshotPacket(new NpcGuideSnapshot(
                    buffer.readLong(),
                    buffer.readComponent(),
                    buffer.readComponent()));
        }

        private static void handle(
                GuideSnapshotPacket packet,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (!context.getDirection().getReceptionSide().isClient()) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> NpcClient.receiveGuideSnapshot(packet.snapshot)));
            context.setPacketHandled(true);
        }
    }
}
