package io.github.yu1sh.reality.npcs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Server-owned guide records stored in the current server world's save. */
final class GuideSavedData extends SavedData {
    static final String DATA_NAME = "reality_npcs";
    private static final String GUIDES_TAG = "guides";

    private final Map<String, GuideRecord> guides = new HashMap<>();

    private GuideSavedData() {
    }

    static GuideSavedData create() {
        return new GuideSavedData();
    }

    static GuideSavedData load(CompoundTag tag) {
        GuideSavedData data = new GuideSavedData();
        ListTag guideTags = tag.getList(GUIDES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < guideTags.size(); index++) {
            CompoundTag guideTag = guideTags.getCompound(index);
            String stableId = guideTag.getString("stable_id");
            String dimension = guideTag.getString("dimension");
            if (!NpcManager.isStableId(stableId) || dimension.isEmpty()) {
                continue;
            }

            try {
                UUID entityUuid = guideTag.getUUID("entity_uuid");
                BlockPos anchor = new BlockPos(
                        guideTag.getInt("anchor_x"),
                        guideTag.getInt("anchor_y"),
                        guideTag.getInt("anchor_z"));
                boolean enabled = guideTag.getBoolean("enabled");
                data.guides.put(stableId, new GuideRecord(
                        stableId, entityUuid, dimension, anchor, enabled));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed records without preventing the world from loading.
            }
        }
        return data;
    }

    static GuideSavedData forServer(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                GuideSavedData::load,
                GuideSavedData::create,
                DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag guideTags = new ListTag();
        List<GuideRecord> records = new ArrayList<>(guides.values());
        records.sort(Comparator.comparing(GuideRecord::stableId));
        for (GuideRecord guide : records) {
            CompoundTag guideTag = new CompoundTag();
            guideTag.putString("stable_id", guide.stableId());
            guideTag.putUUID("entity_uuid", guide.entityUuid());
            guideTag.putString("dimension", guide.dimension());
            guideTag.putInt("anchor_x", guide.anchor().getX());
            guideTag.putInt("anchor_y", guide.anchor().getY());
            guideTag.putInt("anchor_z", guide.anchor().getZ());
            guideTag.putBoolean("enabled", guide.enabled());
            guideTags.add(guideTag);
        }
        tag.put(GUIDES_TAG, guideTags);
        return tag;
    }

    GuideRecord get(String stableId) {
        return guides.get(stableId);
    }

    void put(GuideRecord guide) {
        guides.put(guide.stableId(), guide);
    }

    GuideRecord remove(String stableId) {
        return guides.remove(stableId);
    }

    List<GuideRecord> sortedGuides() {
        List<GuideRecord> records = new ArrayList<>(guides.values());
        records.sort(Comparator.comparing(GuideRecord::stableId));
        return records;
    }

    static final class GuideRecord {
        private final String stableId;
        private UUID entityUuid;
        private final String dimension;
        private final BlockPos anchor;
        private boolean enabled;

        GuideRecord(
                String stableId,
                UUID entityUuid,
                String dimension,
                BlockPos anchor,
                boolean enabled) {
            this.stableId = stableId;
            this.entityUuid = entityUuid;
            this.dimension = dimension;
            this.anchor = new BlockPos(anchor);
            this.enabled = enabled;
        }

        String stableId() {
            return stableId;
        }

        UUID entityUuid() {
            return entityUuid;
        }

        void setEntityUuid(UUID entityUuid) {
            this.entityUuid = entityUuid;
        }

        String dimension() {
            return dimension;
        }

        BlockPos anchor() {
            return anchor;
        }

        boolean enabled() {
            return enabled;
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
