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
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Command, lifecycle, persistence, and bounded-behavior owner for guide NPCs. */
final class NpcManager {
    static final int SERVER_CAP = 32;
    static final int WORLD_DIMENSION_CAP = 16;
    static final int SPAWN_ATTEMPT_INTERVAL_TICKS = 30 * 20;
    static final int AI_UPDATE_INTERVAL_TICKS = 20;
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
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        GuideSavedData data = GuideSavedData.forServer(level.getServer());
        GuideSavedData.GuideRecord guide = findByEntityUuid(data, entity.getUUID());
        return guide != null
                && guide.enabled()
                && guide.dimension().equals(level.dimension().location().toString());
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

    private static int spawnForPlayer(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String dimension = dimensionOf(source.getLevel());
        if (!begin(context, "spawn", null, dimension)) {
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return reject(context, "spawn", null, dimension, "player_position_required");
        }
        return spawn(context, player.serverLevel(), player.blockPosition(), "player_position");
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

        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = context.getSource().getServer().getLevel(levelKey);
        if (level == null) {
            return reject(context, "spawn", null, dimension, "dimension_missing");
        }

        BlockPos anchor = new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
        return spawn(context, level, anchor, "console_coordinates");
    }

    private static int spawn(
            CommandContext<CommandSourceStack> context,
            ServerLevel level,
            BlockPos anchor,
            String reason) {
        MinecraftServer server = context.getSource().getServer();
        GuideSavedData data = GuideSavedData.forServer(server);
        String dimension = dimensionOf(level);
        if (!level.isInWorldBounds(anchor)) {
            return reject(context, "spawn", null, dimension, "anchor_out_of_world_bounds");
        }
        if (activeCount(data, null) >= SERVER_CAP) {
            return reject(context, "spawn", null, dimension, "server_cap_32");
        }
        if (activeCountInDimension(data, dimension, null) >= WORLD_DIMENSION_CAP) {
            return reject(context, "spawn", null, dimension, "world_dimension_cap_16");
        }

        String stableId = "guide-" + UUID.randomUUID();
        GuideSavedData.GuideRecord guide = new GuideSavedData.GuideRecord(
                stableId, UUID.randomUUID(), dimension, anchor, true);
        Villager villager = createGuide(level, guide);
        if (villager == null) {
            return reject(context, "spawn", stableId, dimension, "no_safe_spawn_position");
        }
        if (!level.addFreshEntity(villager)) {
            return reject(context, "spawn", stableId, dimension, "entity_add_rejected");
        }

        data.put(guide);
        data.setDirty();
        finish(context, "spawn", stableId, dimension, reason, "success");
        sendSuccess(context, "guide_spawned id=" + stableId + " dimension=" + dimension);
        return 1;
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
        if (guide == null) {
            return reject(context, "disable", stableId, dimension, "stable_id_missing");
        }
        if (!guide.enabled()) {
            return reject(context, "disable", stableId, dimension, "already_disabled");
        }

        ServerLevel level = findLevel(context.getSource().getServer(), guide.dimension());
        Entity entity = level == null ? null : findEntity(level, guide);
        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
        guide.setEnabled(false);
        data.setDirty();
        finish(context, "disable", stableId, guide.dimension(),
                entity == null ? "entity_missing" : "operator_disable", "success");
        sendSuccess(context, "guide_disabled id=" + stableId);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> context) {
        String stableId = StringArgumentType.getString(context, "stable_id");
        GuideSavedData data = GuideSavedData.forServer(context.getSource().getServer());
        GuideSavedData.GuideRecord guide = data.get(stableId);
        String dimension = guide == null ? dimensionOf(context.getSource().getLevel()) : guide.dimension();
        if (!begin(context, "delete", stableId, dimension)) {
            return 0;
        }
        if (guide == null) {
            return reject(context, "delete", stableId, dimension, "stable_id_missing");
        }

        ServerLevel level = findLevel(context.getSource().getServer(), guide.dimension());
        Entity entity = level == null ? null : findEntity(level, guide);
        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
        data.remove(stableId);
        data.setDirty();
        finish(context, "delete", stableId, guide.dimension(),
                entity == null ? "entity_missing" : "operator_delete", "success");
        sendSuccess(context, "guide_deleted id=" + stableId);
        return 1;
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
        if (guide == null) {
            return reject(context, "recreate", stableId, dimension, "stable_id_missing");
        }

        ServerLevel level = findLevel(server, guide.dimension());
        if (level == null) {
            return reject(context, "recreate", stableId, guide.dimension(), "dimension_missing");
        }
        if (!guide.enabled() && activeCount(data, null) >= SERVER_CAP) {
            return reject(context, "recreate", stableId, guide.dimension(), "server_cap_32");
        }
        if (!guide.enabled()
                && activeCountInDimension(data, guide.dimension(), null) >= WORLD_DIMENSION_CAP) {
            return reject(context, "recreate", stableId, guide.dimension(), "world_dimension_cap_16");
        }

        Entity previous = guide.enabled() ? findEntity(level, guide) : null;
        UUID previousUuid = guide.entityUuid();
        guide.setEntityUuid(UUID.randomUUID());
        Villager replacement = createGuide(level, guide);
        if (replacement == null) {
            guide.setEntityUuid(previousUuid);
            return reject(context, "recreate", stableId, guide.dimension(), "no_safe_spawn_position");
        }
        if (!level.addFreshEntity(replacement)) {
            guide.setEntityUuid(previousUuid);
            return reject(context, "recreate", stableId, guide.dimension(), "entity_add_rejected");
        }
        if (previous != null) {
            previous.remove(Entity.RemovalReason.DISCARDED);
        }
        guide.setEnabled(true);
        data.setDirty();
        finish(context, "recreate", stableId, guide.dimension(), "operator_recreate", "success");
        sendSuccess(context, "guide_recreated id=" + stableId + " entity_uuid=" + guide.entityUuid());
        return 1;
    }

    private static boolean begin(
            CommandContext<CommandSourceStack> context,
            String action,
            String stableId,
            String dimension) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        String actor = actor(source);
        if (!authorized(source)) {
            AuditLog.append(server, actor, action, stableId, dimension,
                    "permission_level_2_or_console_required", "rejected");
            source.sendFailure(Component.literal("only permission level 2+ players or the server console may use this command"));
            return false;
        }

        int now = server.getTickCount();
        Integer nextAllowed = NEXT_ALLOWED_TICK.get(actor);
        if (nextAllowed != null && now < nextAllowed) {
            AuditLog.append(server, actor, action, stableId, dimension,
                    "operator_rate_limit_30_seconds", "rejected");
            source.sendFailure(Component.literal("reality-npcs operator rate limit: try again later"));
            return false;
        }
        NEXT_ALLOWED_TICK.put(actor, now + SPAWN_ATTEMPT_INTERVAL_TICKS);

        if (!AuditLog.prepare(server)) {
            source.sendFailure(Component.literal("reality-npcs audit log is unavailable; operation rejected"));
            return false;
        }
        return true;
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

    private static void finish(
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

    private static String dimensionOf(ServerLevel level) {
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
        data.setDirty();
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
