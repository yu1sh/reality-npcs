package io.github.yu1sh.reality.npcs;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Command, lifecycle, persistence, and bounded-behavior owner for guide NPCs. */
final class NpcManager {
    static final int SERVER_CAP = 32;
    static final int WORLD_DIMENSION_CAP = 16;
    static final int SPAWN_ATTEMPT_INTERVAL_TICKS = 30 * 20;
    static final int AI_UPDATE_INTERVAL_TICKS = 20;
    static final int MAX_DIMENSION_LENGTH = 128;
    static final int MAX_GUIDE_TEXT_LENGTH = 280;
    static final double ANCHOR_RADIUS = 16.0D;
    private static final double ANCHOR_RADIUS_SQUARED = ANCHOR_RADIUS * ANCHOR_RADIUS;
    private static final Pattern STABLE_ID = Pattern.compile(
            "guide-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Logger LOGGER = LoggerFactory.getLogger(RealityNpcsMod.MOD_ID);

    private static final Map<String, Integer> NEXT_ALLOWED_TICK = new HashMap<>();

    private NpcManager() {
    }

    static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("realitynpcs")
                        .then(Commands.literal("spawn")
                                .then(Commands.literal("guide")
                                        .executes(NpcManager::spawnForPlayer)
                                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                                .executes(NpcManager::spawnForConsole)))))))
                        .then(Commands.literal("gui")
                                .executes(NpcAdminController::openGui))
                        .then(Commands.literal("list")
                                .executes(NpcManager::list))
                        .then(Commands.literal("disable")
                                .then(Commands.argument("stable_id", StringArgumentType.word())
                                        .executes(NpcManager::disable)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("stable_id", StringArgumentType.word())
                                        .executes(NpcManager::delete)))
                        .then(Commands.literal("recreate")
                                .then(Commands.argument("stable_id", StringArgumentType.word())
                                        .executes(NpcManager::recreate))));
    }

    static void restore(MinecraftServer server) {
        GuideSavedData data = GuideSavedData.forServer(server);
        AuditLog.prepare(server);
        for (GuideSavedData.GuideRecord guide : data.sortedGuides()) {
            ServerLevel level = findLevel(server, guide.dimension());
            if (level == null) {
                disableMissing(server, data, guide, "dimension_missing");
                continue;
            }

            Entity entity = findEntity(level, guide);
            if (!guide.enabled()) {
                if (entity != null) {
                    entity.remove(Entity.RemovalReason.DISCARDED);
                }
                continue;
            }

            if (!(entity instanceof Villager villager)) {
                disableMissing(server, data, guide, "entity_missing");
                continue;
            }
            configureGuide(villager, guide);
        }
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % AI_UPDATE_INTERVAL_TICKS != 0) {
            return;
        }

        GuideSavedData data = GuideSavedData.forServer(server);
        for (GuideSavedData.GuideRecord guide : data.sortedGuides()) {
            if (!guide.enabled()) {
                continue;
            }

            ServerLevel level = findLevel(server, guide.dimension());
            if (level == null) {
                disableMissing(server, data, guide, "dimension_missing");
                continue;
            }

            Entity entity = findEntity(level, guide);
            if (!(entity instanceof Villager villager)) {
                disableMissing(server, data, guide, "entity_missing");
                continue;
            }
            configureGuide(villager, guide);

            if (villager.distanceToSqr(
                    guide.anchor().getX() + 0.5D,
                    guide.anchor().getY(),
                    guide.anchor().getZ() + 0.5D) > ANCHOR_RADIUS_SQUARED) {
                BlockPos safePosition = findSafePosition(level, villager, guide.anchor());
                if (safePosition == null) {
                    LOGGER.warn("no safe anchor position found for guide {}", guide.stableId());
                    AuditLog.append(
                            server,
                            "console",
                            "anchor_return",
                            guide.stableId(),
                            guide.dimension(),
                            "no_safe_position",
                            "rejected");
                    continue;
                }
                villager.teleportTo(
                        safePosition.getX() + 0.5D,
                        safePosition.getY(),
                        safePosition.getZ() + 0.5D);
            }
        }
    }

    static boolean isTrackedGuide(Entity entity) {
        return activeGuideForInteraction(entity) != null;
    }

    static GuideSavedData.GuideRecord activeGuideForInteraction(Entity entity) {
        if (!(entity instanceof Villager)
                || !(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        GuideSavedData data = GuideSavedData.forServer(level.getServer());
        GuideSavedData.GuideRecord guide = findByEntityUuid(data, entity.getUUID());
        if (guide == null
                || !guide.enabled()
                || !guide.dimension().equals(level.dimension().location().toString())) {
            return null;
        }
        Entity currentEntity = level.getEntity(guide.entityUuid());
        return currentEntity == entity ? guide : null;
    }

    static boolean isTrackedGuideInAnyState(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        return findByEntityUuid(
                GuideSavedData.forServer(level.getServer()),
                entity.getUUID()) != null;
    }

    static String stableIdFor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return "unknown";
        }
        GuideSavedData.GuideRecord guide = findByEntityUuid(
                GuideSavedData.forServer(level.getServer()),
                entity.getUUID());
        return guide == null ? "unknown" : guide.stableId();
    }

    static NpcAdminSnapshot snapshot(MinecraftServer server, long sessionId) {
        GuideSavedData data = GuideSavedData.forServer(server);
        List<NpcAdminSnapshot.Entry> entries = new ArrayList<>();
        for (GuideSavedData.GuideRecord guide : data.sortedGuides()) {
            ServerLevel level = findLevel(server, guide.dimension());
            boolean entityPresent = level != null && findEntity(level, guide) != null;
            entries.add(new NpcAdminSnapshot.Entry(
                    guide.stableId(),
                    guide.enabled(),
                    guide.dimension(),
                    guide.anchor().getX(),
                    guide.anchor().getY(),
                    guide.anchor().getZ(),
                    guide.entityUuid().toString(),
                    entityPresent,
                    guide.guideText()));
        }
        return new NpcAdminSnapshot(sessionId, data.revision(), entries);
    }

    static OperationResult setGuideText(
            GuideSavedData data,
            String stableId,
            String requestedText,
            String successReason,
            String missingDimension) {
        String dimension = missingDimension;
        if (!isStableId(stableId)) {
            return rejected(stableId, dimension, "stable_id_invalid");
        }
        String inputReason = guideTextInputReason(requestedText);
        if (inputReason != null) {
            return rejected(stableId, dimension, inputReason);
        }

        GuideSavedData.GuideRecord guide = data.get(stableId);
        if (guide == null) {
            return rejected(stableId, dimension, "stable_id_missing");
        }
        dimension = guide.dimension();
        String guideText = normalizeGuideText(requestedText);
        if (guide.guideText().equals(guideText)) {
            return succeeded(
                    stableId,
                    dimension,
                    "guide_text_unchanged",
                    "guide_text_unchanged id=" + stableId);
        }

        guide.setGuideText(guideText);
        data.changed();
        return succeeded(
                stableId,
                dimension,
                successReason,
                "guide_text_saved id=" + stableId);
    }

    private static int spawnForPlayer(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String dimension = dimensionOf(source.getLevel());
        if (!begin(context, "spawn", null, dimension)) {
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return reject(context, "spawn", null, dimension, "player_position_required");
        }
        GuideSavedData data = GuideSavedData.forServer(source.getServer());
        OperationResult result = spawn(
                source.getServer(),
                data,
                player.serverLevel(),
                player.blockPosition(),
                "player_position");
        return complete(context, "spawn", result);
    }

    private static int spawnForConsole(CommandContext<CommandSourceStack> context) {
        ResourceLocation dimensionId = ResourceLocationArgument.getId(context, "dimension");
        String dimension = dimensionId.toString();
        if (!begin(context, "spawn", null, dimension)) {
            return 0;
        }
        if (!isConsole(context.getSource())) {
            return reject(context, "spawn", null, dimension, "console_coordinates_only");
        }

        MinecraftServer server = context.getSource().getServer();
        GuideSavedData data = GuideSavedData.forServer(server);
        OperationResult result = spawnAtCoordinates(
                server,
                data,
                dimension,
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"),
                "console_coordinates");
        return complete(context, "spawn", result);
    }

    static OperationResult spawn(
            MinecraftServer server,
            GuideSavedData data,
            ServerLevel level,
            BlockPos anchor,
            String reason) {
        String dimension = dimensionOf(level);
        if (level == null) {
            return rejected(null, dimension, "dimension_missing");
        }
        if (!level.isInWorldBounds(anchor)) {
            return rejected(null, dimension, "anchor_out_of_world_bounds");
        }
        if (activeCount(data, null) >= SERVER_CAP) {
            return rejected(null, dimension, "server_cap_32");
        }
        if (activeCountInDimension(data, dimension, null) >= WORLD_DIMENSION_CAP) {
            return rejected(null, dimension, "world_dimension_cap_16");
        }

        String stableId = "guide-" + UUID.randomUUID();
        GuideSavedData.GuideRecord guide = new GuideSavedData.GuideRecord(
                stableId, UUID.randomUUID(), dimension, anchor, true);
        Villager villager = createGuide(level, guide);
        if (villager == null) {
            return rejected(stableId, dimension, "no_safe_spawn_position");
        }
        if (!level.addFreshEntity(villager)) {
            return rejected(stableId, dimension, "entity_add_rejected");
        }

        data.put(guide);
        data.changed();
        return succeeded(
                stableId,
                dimension,
                reason,
                "guide_spawned id=" + stableId + " dimension=" + dimension);
    }

    static OperationResult spawnAtCoordinates(
            MinecraftServer server,
            GuideSavedData data,
            String requestedDimension,
            int x,
            int y,
            int z,
            String reason) {
        if (requestedDimension == null
                || requestedDimension.isEmpty()
                || requestedDimension.length() > MAX_DIMENSION_LENGTH) {
            return rejected(null, "unknown", "dimension_invalid");
        }

        ResourceLocation dimensionId = ResourceLocation.tryParse(requestedDimension);
        if (dimensionId == null) {
            return rejected(null, requestedDimension, "dimension_invalid");
        }

        String dimension = dimensionId.toString();
        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = server.getLevel(levelKey);
        if (level == null) {
            return rejected(null, dimension, "dimension_missing");
        }

        return spawn(server, data, level, new BlockPos(x, y, z), reason);
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        if (!begin(context, "list", null, "*")) {
            return 0;
        }
        GuideSavedData data = GuideSavedData.forServer(context.getSource().getServer());
        List<GuideSavedData.GuideRecord> guides = data.sortedGuides();
        finish(context, "list", null, "*", "operator_list", "success");
        if (guides.isEmpty()) {
            sendSuccess(context, "reality-npcs: no guides");
            return 1;
        }
        sendSuccess(context, "reality-npcs: " + guides.size() + " guide record(s)");
        for (GuideSavedData.GuideRecord guide : guides) {
            String state = guide.enabled() ? "enabled" : "disabled";
            sendSuccess(context, guide.stableId() + " " + state
                    + " dimension=" + guide.dimension()
                    + " anchor=" + format(guide.anchor())
                    + " entity_uuid=" + guide.entityUuid());
        }
        return 1;
    }

    private static int disable(CommandContext<CommandSourceStack> context) {
        String stableId = StringArgumentType.getString(context, "stable_id");
        GuideSavedData data = GuideSavedData.forServer(context.getSource().getServer());
        GuideSavedData.GuideRecord guide = data.get(stableId);
        String dimension = guide == null ? dimensionOf(context.getSource().getLevel()) : guide.dimension();
        if (!begin(context, "disable", stableId, dimension)) {
            return 0;
        }
        return complete(
                context,
                "disable",
                disable(
                        context.getSource().getServer(),
                        data,
                        stableId,
                        "operator_disable",
                        dimension));
    }

    private static int delete(CommandContext<CommandSourceStack> context) {
        String stableId = StringArgumentType.getString(context, "stable_id");
        GuideSavedData data = GuideSavedData.forServer(context.getSource().getServer());
        GuideSavedData.GuideRecord guide = data.get(stableId);
        String dimension = guide == null ? dimensionOf(context.getSource().getLevel()) : guide.dimension();
        if (!begin(context, "delete", stableId, dimension)) {
            return 0;
        }
        return complete(
                context,
                "delete",
                delete(
                        context.getSource().getServer(),
                        data,
                        stableId,
                        "operator_delete",
                        dimension));
    }

    private static int recreate(CommandContext<CommandSourceStack> context) {
        String stableId = StringArgumentType.getString(context, "stable_id");
        MinecraftServer server = context.getSource().getServer();
        GuideSavedData data = GuideSavedData.forServer(server);
        GuideSavedData.GuideRecord guide = data.get(stableId);
        String dimension = guide == null ? dimensionOf(context.getSource().getLevel()) : guide.dimension();
        if (!begin(context, "recreate", stableId, dimension)) {
            return 0;
        }
        return complete(
                context,
                "recreate",
                recreate(server, data, stableId, "operator_recreate", dimension));
    }

    static OperationResult disable(
            MinecraftServer server,
            GuideSavedData data,
            String stableId,
            String successReason,
            String missingDimension) {
        GuideSavedData.GuideRecord guide = data.get(stableId);
        String dimension = guide == null ? missingDimension : guide.dimension();
        if (guide == null) {
            return rejected(stableId, dimension, "stable_id_missing");
        }
        if (!guide.enabled()) {
            return rejected(stableId, dimension, "already_disabled");
        }

        ServerLevel level = findLevel(server, guide.dimension());
        Entity entity = level == null ? null : findEntity(level, guide);
        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
        guide.setEnabled(false);
        data.changed();
        return succeeded(
                stableId,
                guide.dimension(),
                entity == null ? "entity_missing" : successReason,
                "guide_disabled id=" + stableId);
    }

    static OperationResult delete(
            MinecraftServer server,
            GuideSavedData data,
            String stableId,
            String successReason,
            String missingDimension) {
        GuideSavedData.GuideRecord guide = data.get(stableId);
        String dimension = guide == null ? missingDimension : guide.dimension();
        if (guide == null) {
            return rejected(stableId, dimension, "stable_id_missing");
        }

        ServerLevel level = findLevel(server, guide.dimension());
        Entity entity = level == null ? null : findEntity(level, guide);
        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
        data.remove(stableId);
        data.changed();
        return succeeded(
                stableId,
                guide.dimension(),
                entity == null ? "entity_missing" : successReason,
                "guide_deleted id=" + stableId);
    }

    static OperationResult recreate(
            MinecraftServer server,
            GuideSavedData data,
            String stableId,
            String successReason,
            String missingDimension) {
        GuideSavedData.GuideRecord guide = data.get(stableId);
        String dimension = guide == null ? missingDimension : guide.dimension();
        if (guide == null) {
            return rejected(stableId, dimension, "stable_id_missing");
        }

        ServerLevel level = findLevel(server, guide.dimension());
        if (level == null) {
            return rejected(stableId, guide.dimension(), "dimension_missing");
        }
        if (!guide.enabled() && activeCount(data, null) >= SERVER_CAP) {
            return rejected(stableId, guide.dimension(), "server_cap_32");
        }
        if (!guide.enabled()
                && activeCountInDimension(data, guide.dimension(), null) >= WORLD_DIMENSION_CAP) {
            return rejected(stableId, guide.dimension(), "world_dimension_cap_16");
        }

        Entity previous = guide.enabled() ? findEntity(level, guide) : null;
        UUID previousUuid = guide.entityUuid();
        guide.setEntityUuid(UUID.randomUUID());
        Villager replacement = createGuide(level, guide);
        if (replacement == null) {
            guide.setEntityUuid(previousUuid);
            return rejected(stableId, guide.dimension(), "no_safe_spawn_position");
        }
        if (!level.addFreshEntity(replacement)) {
            guide.setEntityUuid(previousUuid);
            return rejected(stableId, guide.dimension(), "entity_add_rejected");
        }
        if (previous != null) {
            previous.remove(Entity.RemovalReason.DISCARDED);
        }
        guide.setEnabled(true);
        data.changed();
        return succeeded(
                stableId,
                guide.dimension(),
                successReason,
                "guide_recreated id=" + stableId + " entity_uuid=" + guide.entityUuid());
    }

    private static OperationResult succeeded(
            String stableId,
            String dimension,
            String reason,
            String message) {
        return new OperationResult(true, stableId, dimension, reason, message);
    }

    static OperationResult rejected(String stableId, String dimension, String reason) {
        return new OperationResult(false, stableId, dimension, reason, "");
    }

    static String guideTextInputReason(String value) {
        if (value == null) {
            return "guide_text_invalid";
        }
        if (value.length() > MAX_GUIDE_TEXT_LENGTH
                || value.codePointCount(0, value.length()) > MAX_GUIDE_TEXT_LENGTH) {
            return "guide_text_too_long";
        }
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                return "guide_text_plain_text_required";
            }
            index += Character.charCount(codePoint);
        }
        return null;
    }

    static boolean isGuideTextValid(String value) {
        return guideTextInputReason(value) == null;
    }

    private static String normalizeGuideText(String value) {
        return value.isBlank() ? "" : value;
    }

    record OperationResult(
            boolean success,
            String stableId,
            String dimension,
            String reason,
            String message) {
    }

    private static boolean begin(
            CommandContext<CommandSourceStack> context,
            String action,
            String stableId,
            String dimension) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        return beginOperation(
                server,
                actor(source),
                authorized(source),
                action,
                stableId,
                dimension,
                message -> source.sendFailure(Component.literal(message)));
    }

    static boolean beginGuiMutation(
            ServerPlayer player,
            String action,
            String stableId,
            String dimension) {
        return beginOperation(
                player.getServer(),
                actor(player),
                player.hasPermissions(2),
                action,
                stableId,
                dimension,
                message -> player.sendSystemMessage(Component.literal(message)));
    }

    static boolean beginGuiRead(
            ServerPlayer player,
            String action,
            String stableId,
            String dimension) {
        MinecraftServer server = player.getServer();
        String actor = actor(player);
        if (!player.hasPermissions(2)) {
            AuditLog.append(
                    server,
                    actor,
                    action,
                    stableId,
                    dimension,
                    "permission_level_2_or_console_required",
                    "rejected");
            player.sendSystemMessage(Component.literal(
                    "only permission level 2+ players may use the reality-npcs GUI"));
            return false;
        }
        if (!AuditLog.prepare(server)) {
            player.sendSystemMessage(Component.literal(
                    "reality-npcs audit log is unavailable; operation rejected"));
            return false;
        }
        return true;
    }

    private static boolean beginOperation(
            MinecraftServer server,
            String actor,
            boolean authorized,
            String action,
            String stableId,
            String dimension,
            Consumer<String> failure) {
        if (!authorized) {
            AuditLog.append(
                    server,
                    actor,
                    action,
                    stableId,
                    dimension,
                    "permission_level_2_or_console_required",
                    "rejected");
            failure.accept(
                    "only permission level 2+ players or the server console may use this command");
            return false;
        }

        int now = server.getTickCount();
        Integer nextAllowed = NEXT_ALLOWED_TICK.get(actor);
        if (nextAllowed != null && now < nextAllowed) {
            AuditLog.append(
                    server,
                    actor,
                    action,
                    stableId,
                    dimension,
                    "operator_rate_limit_30_seconds",
                    "rejected");
            failure.accept("reality-npcs operator rate limit: try again later");
            return false;
        }
        NEXT_ALLOWED_TICK.put(actor, now + SPAWN_ATTEMPT_INTERVAL_TICKS);

        if (!AuditLog.prepare(server)) {
            failure.accept("reality-npcs audit log is unavailable; operation rejected");
            return false;
        }
        return true;
    }

    private static int complete(
            CommandContext<CommandSourceStack> context,
            String action,
            OperationResult result) {
        if (!result.success()) {
            return reject(
                    context,
                    action,
                    result.stableId(),
                    result.dimension(),
                    result.reason());
        }
        finish(
                context,
                action,
                result.stableId(),
                result.dimension(),
                result.reason(),
                "success");
        sendSuccess(context, result.message());
        return 1;
    }

    private static int reject(
            CommandContext<CommandSourceStack> context,
            String action,
            String stableId,
            String dimension,
            String reason) {
        finish(context, action, stableId, dimension, reason, "rejected");
        context.getSource().sendFailure(Component.literal("reality-npcs operation rejected: " + reason));
        return 0;
    }

    static void finish(
            CommandContext<CommandSourceStack> context,
            String action,
            String stableId,
            String dimension,
            String reason,
            String result) {
        AuditLog.append(
                context.getSource().getServer(),
                actor(context.getSource()),
                action,
                stableId,
                dimension,
                reason,
                result);
    }

    static void finish(
            MinecraftServer server,
            String actor,
            String action,
            String stableId,
            String dimension,
            String reason,
            String result) {
        AuditLog.append(server, actor, action, stableId, dimension, reason, result);
    }

    private static void sendSuccess(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    private static boolean authorized(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer && source.hasPermission(2)
                || isConsole(source);
    }

    private static boolean isConsole(CommandSourceStack source) {
        return source.getEntity() == null && source.source == source.getServer();
    }

    private static String actor(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player.getUUID().toString();
        }
        if (isConsole(source)) {
            return "console";
        }
        return "unsupported:" + source.getTextName();
    }

    static String actor(ServerPlayer player) {
        return player.getUUID().toString();
    }

    static String dimensionOf(ServerLevel level) {
        return level == null ? "unknown" : level.dimension().location().toString();
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static Entity findEntity(ServerLevel level, GuideSavedData.GuideRecord guide) {
        loadAnchorChunks(level, guide.anchor());
        Entity entity = level.getEntity(guide.entityUuid());
        if (entity != null && entity.level() == level) {
            return entity;
        }
        return null;
    }

    private static void loadAnchorChunks(ServerLevel level, BlockPos anchor) {
        int chunkX = anchor.getX() >> 4;
        int chunkZ = anchor.getZ() >> 4;
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                level.getChunk(x, z);
            }
        }
    }

    private static Villager createGuide(ServerLevel level, GuideSavedData.GuideRecord guide) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) {
            return null;
        }
        villager.setUUID(guide.entityUuid());
        villager.moveTo(
                guide.anchor().getX() + 0.5D,
                guide.anchor().getY(),
                guide.anchor().getZ() + 0.5D,
                0.0F,
                0.0F);
        BlockPos safePosition = findSafePosition(level, villager, guide.anchor());
        if (safePosition == null) {
            return null;
        }
        villager.moveTo(
                safePosition.getX() + 0.5D,
                safePosition.getY(),
                safePosition.getZ() + 0.5D,
                0.0F,
                0.0F);
        configureGuide(villager, guide);
        return villager;
    }

    private static void configureGuide(Villager villager, GuideSavedData.GuideRecord guide) {
        villager.setNoAi(true);
        villager.setInvulnerable(true);
        villager.setPersistenceRequired();
        villager.setCustomName(Component.literal("Guide " + guide.stableId()));
        villager.setCustomNameVisible(true);
        villager.setOffers(new MerchantOffers());
        villager.setVillagerXp(0);
    }

    private static BlockPos findSafePosition(ServerLevel level, Entity entity, BlockPos anchor) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (dx * dx + dy * dy + dz * dz > ANCHOR_RADIUS_SQUARED) {
                        continue;
                    }
                    candidates.add(anchor.offset(dx, dy, dz));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate ->
                candidate.distSqr(anchor)));
        for (BlockPos candidate : candidates) {
            if (isSafePosition(level, entity, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isSafePosition(ServerLevel level, Entity entity, BlockPos candidate) {
        if (!level.isInWorldBounds(candidate)
                || !level.getFluidState(candidate).isEmpty()
                || !level.getBlockState(candidate.below()).isFaceSturdy(
                        level, candidate.below(), Direction.UP)) {
            return false;
        }
        double xDelta = candidate.getX() + 0.5D - entity.getX();
        double yDelta = candidate.getY() - entity.getY();
        double zDelta = candidate.getZ() + 0.5D - entity.getZ();
        AABB candidateBox = entity.getBoundingBox().move(xDelta, yDelta, zDelta);
        return level.noCollision(entity, candidateBox);
    }

    private static int activeCount(GuideSavedData data, String excludedStableId) {
        int count = 0;
        for (GuideSavedData.GuideRecord guide : data.sortedGuides()) {
            if (guide.enabled() && !guide.stableId().equals(excludedStableId)) {
                count++;
            }
        }
        return count;
    }

    private static int activeCountInDimension(
            GuideSavedData data,
            String dimension,
            String excludedStableId) {
        int count = 0;
        for (GuideSavedData.GuideRecord guide : data.sortedGuides()) {
            if (guide.enabled()
                    && guide.dimension().equals(dimension)
                    && !guide.stableId().equals(excludedStableId)) {
                count++;
            }
        }
        return count;
    }

    private static GuideSavedData.GuideRecord findByEntityUuid(GuideSavedData data, UUID entityUuid) {
        for (GuideSavedData.GuideRecord guide : data.sortedGuides()) {
            if (guide.entityUuid().equals(entityUuid)) {
                return guide;
            }
        }
        return null;
    }

    private static void disableMissing(
            MinecraftServer server,
            GuideSavedData data,
            GuideSavedData.GuideRecord guide,
            String reason) {
        if (!guide.enabled()) {
            return;
        }
        guide.setEnabled(false);
        data.changed();
        AuditLog.append(
                server,
                "console",
                "restore",
                guide.stableId(),
                guide.dimension(),
                reason,
                "disabled");
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    static boolean isStableId(String value) {
        return value != null && STABLE_ID.matcher(value).matches();
    }
}
