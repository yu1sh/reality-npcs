package io.github.yu1sh.reality.npcs;

import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;

/** Forge command/network adapter for the server-owned administrator menu. */
final class NpcAdminController {
    private NpcAdminController() {
    }

    static int openGui(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("the reality-npcs GUI requires an online player"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        String dimension = NpcManager.dimensionOf(player.serverLevel());
        if (!NpcManager.beginGuiRead(player, "gui", null, dimension)) {
            return 0;
        }

        long sessionId = newSessionId();
        NetworkHooks.openScreen(
                player,
                new SimpleMenuProvider(
                        (containerId, inventory, ignoredPlayer) ->
                                new NpcAdminMenu(containerId, inventory, sessionId),
                        Component.literal("Reality NPCs")),
                buffer -> buffer.writeLong(sessionId));
        NpcManager.finish(
                server,
                NpcManager.actor(player),
                "gui",
                null,
                dimension,
                "operator_gui_open",
                "success");
        sendSnapshot(player, sessionId);
        return 1;
    }

    static void handleRequest(
            ServerPlayer player,
            long sessionId,
            long requestId,
            long snapshotRevision,
            NpcAdminOperation operation,
            String stableId,
            String dimensionInput,
            int x,
            int y,
            int z) {
        if (!(player.containerMenu instanceof NpcAdminMenu menu)
                || menu.sessionId() != sessionId) {
            return;
        }
        if (!menu.acceptRequestId(requestId)) {
            rejectRequest(player, operation, stableId, "request_replay_or_out_of_order");
            sendSnapshot(player, sessionId);
            return;
        }
        if (operation == null) {
            rejectRequest(player, null, stableId, "operation_invalid");
            sendSnapshot(player, sessionId);
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String action = actionOf(operation);
        if (operation == NpcAdminOperation.REFRESH) {
            if (NpcManager.beginGuiRead(player, "list", null, "*")) {
                NpcManager.finish(
                        server,
                        NpcManager.actor(player),
                        "list",
                        null,
                        "*",
                        "gui_snapshot",
                        "success");
            }
            sendSnapshot(player, sessionId);
            return;
        }

        GuideSavedData data = GuideSavedData.forServer(server);
        String requestedStableId = stableId == null ? "" : stableId;
        String requestedDimension = dimensionInput == null ? "" : dimensionInput;
        GuideSavedData.GuideRecord guide = data.get(requestedStableId);
        String dimension = operation == NpcAdminOperation.SPAWN_AT_COORDINATES
                ? requestedDimension.isEmpty()
                        ? NpcManager.dimensionOf(player.serverLevel())
                        : requestedDimension
                : guide == null
                ? NpcManager.dimensionOf(player.serverLevel())
                : guide.dimension();
        if (!NpcManager.beginGuiMutation(player, action, requestedStableId, dimension)) {
            sendSnapshot(player, sessionId);
            return;
        }

        String invalidInput = invalidInputReason(operation, requestedStableId);
        if (invalidInput != null) {
            completeRejected(player, sessionId, action, requestedStableId, dimension, invalidInput);
            return;
        }
        if (snapshotRevision < 0L || snapshotRevision != data.revision()) {
            completeRejected(
                    player,
                    sessionId,
                    action,
                    requestedStableId,
                    dimension,
                    "snapshot_revision_stale");
            return;
        }

        NpcManager.OperationResult result;
        switch (operation) {
            case SPAWN -> result = NpcManager.spawn(
                    server,
                    data,
                    player.serverLevel(),
                    player.blockPosition(),
                    "gui_current_position");
            case SPAWN_AT_COORDINATES -> result = NpcManager.spawnAtCoordinates(
                    server,
                    data,
                    requestedDimension,
                    x,
                    y,
                    z,
                    "gui_coordinates");
            case DISABLE -> result = NpcManager.disable(
                    server, data, requestedStableId, "gui_disable", dimension);
            case DELETE -> result = NpcManager.delete(
                    server, data, requestedStableId, "gui_delete", dimension);
            case RECREATE -> result = NpcManager.recreate(
                    server, data, requestedStableId, "gui_recreate", dimension);
            case REFRESH -> throw new IllegalStateException("refresh handled above");
            default -> result = NpcManager.rejected(requestedStableId, dimension, "operation_invalid");
        }
        NpcManager.finish(
                server,
                NpcManager.actor(player),
                action,
                result.stableId(),
                result.dimension(),
                result.reason(),
                result.success() ? "success" : "rejected");
        player.sendSystemMessage(Component.literal(
                result.success()
                        ? result.message()
                        : "reality-npcs operation rejected: " + result.reason()));
        sendSnapshot(player, sessionId);
    }

    static void handleGuideTextRequest(
            ServerPlayer player,
            long sessionId,
            long requestId,
            long snapshotRevision,
            String stableId,
            String guideText) {
        if (!(player.containerMenu instanceof NpcAdminMenu menu)
                || menu.sessionId() != sessionId) {
            return;
        }
        if (!menu.acceptRequestId(requestId)) {
            rejectRequest(player, "set_guide_text", stableId, "request_replay_or_out_of_order");
            sendSnapshot(player, sessionId);
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String action = "set_guide_text";
        String requestedStableId = stableId == null ? "" : stableId;
        GuideSavedData data = GuideSavedData.forServer(server);
        GuideSavedData.GuideRecord guide = data.get(requestedStableId);
        String dimension = guide == null
                ? NpcManager.dimensionOf(player.serverLevel())
                : guide.dimension();
        if (!NpcManager.beginGuiMutation(player, action, requestedStableId, dimension)) {
            sendSnapshot(player, sessionId);
            return;
        }

        if (!NpcManager.isStableId(requestedStableId)) {
            completeRejected(player, sessionId, action, requestedStableId, dimension, "stable_id_invalid");
            return;
        }
        String invalidText = NpcManager.guideTextInputReason(guideText);
        if (invalidText != null) {
            completeRejected(player, sessionId, action, requestedStableId, dimension, invalidText);
            return;
        }
        if (snapshotRevision < 0L || snapshotRevision != data.revision()) {
            completeRejected(
                    player,
                    sessionId,
                    action,
                    requestedStableId,
                    dimension,
                    "snapshot_revision_stale");
            return;
        }

        NpcManager.OperationResult result = NpcManager.setGuideText(
                data,
                requestedStableId,
                guideText,
                "gui_set_guide_text",
                dimension);
        NpcManager.finish(
                server,
                NpcManager.actor(player),
                action,
                result.stableId(),
                result.dimension(),
                result.reason(),
                result.success() ? "success" : "rejected");
        player.sendSystemMessage(Component.literal(
                result.success()
                        ? result.message()
                        : "reality-npcs operation rejected: " + result.reason()));
        sendSnapshot(player, sessionId);
    }

    private static void completeRejected(
            ServerPlayer player,
            long sessionId,
            String action,
            String stableId,
            String dimension,
            String reason) {
        MinecraftServer server = player.getServer();
        NpcManager.finish(
                server,
                NpcManager.actor(player),
                action,
                stableId,
                dimension,
                reason,
                "rejected");
        player.sendSystemMessage(Component.literal(
                "reality-npcs operation rejected: " + reason
                        + ("snapshot_revision_stale".equals(reason) ? "; refresh the GUI" : "")));
        sendSnapshot(player, sessionId);
    }

    private static String invalidInputReason(NpcAdminOperation operation, String stableId) {
        if (operation == NpcAdminOperation.SPAWN
                || operation == NpcAdminOperation.SPAWN_AT_COORDINATES) {
            return stableId.isEmpty() ? null : "stable_id_not_allowed_for_spawn";
        }
        return NpcManager.isStableId(stableId) ? null : "stable_id_invalid";
    }

    private static void sendSnapshot(ServerPlayer player, long sessionId) {
        NpcNetwork.sendSnapshot(player, NpcManager.snapshot(player.getServer(), sessionId));
    }

    private static void rejectRequest(
            ServerPlayer player,
            NpcAdminOperation operation,
            String stableId,
            String reason) {
        rejectRequest(
                player,
                operation == null ? "gui_request" : actionOf(operation),
                stableId,
                reason);
    }

    private static void rejectRequest(
            ServerPlayer player,
            String action,
            String stableId,
            String reason) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            AuditLog.prepare(server);
            AuditLog.append(
                    server,
                    NpcManager.actor(player),
                    action,
                    stableId == null || stableId.isEmpty() ? null : stableId,
                    NpcManager.dimensionOf(player.serverLevel()),
                    reason,
                    "rejected");
        }
        player.sendSystemMessage(Component.literal("reality-npcs operation rejected: " + reason));
    }

    private static String actionOf(NpcAdminOperation operation) {
        return switch (operation) {
            case REFRESH -> "list";
            case SPAWN -> "spawn";
            case DISABLE -> "disable";
            case DELETE -> "delete";
            case RECREATE -> "recreate";
            case SPAWN_AT_COORDINATES -> "spawn";
        };
    }

    private static long newSessionId() {
        long sessionId;
        do {
            sessionId = UUID.randomUUID().getLeastSignificantBits();
        } while (sessionId == 0L);
        return sessionId;
    }
}
