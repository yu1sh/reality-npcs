package io.github.yu1sh.reality.npcs;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkHooks;

/** Forge interaction adapter for a server-authorized read-only guide menu. */
final class NpcGuideController {
    private NpcGuideController() {
    }

    static boolean openGui(ServerPlayer player, Entity target) {
        GuideSavedData.GuideRecord guide = NpcManager.activeGuideForInteraction(target);
        if (guide == null) {
            return false;
        }
        if (player.containerMenu instanceof NpcGuideMenu) {
            return true;
        }

        long sessionId = newSessionId();
        Component title = Component.translatable("reality_npcs.guide.title");
        Component body = guide.guideText().isEmpty()
                ? Component.translatable("reality_npcs.guide.default_body")
                : Component.literal(guide.guideText());
        NetworkHooks.openScreen(
                player,
                new SimpleMenuProvider(
                        (containerId, inventory, ignoredPlayer) ->
                                new NpcGuideMenu(containerId, inventory, sessionId),
                        title),
                buffer -> buffer.writeLong(sessionId));
        NpcNetwork.sendGuideSnapshot(
                player,
                new NpcGuideSnapshot(sessionId, title, body));
        return true;
    }

    private static long newSessionId() {
        long sessionId;
        do {
            sessionId = UUID.randomUUID().getLeastSignificantBits();
        } while (sessionId == 0L);
        return sessionId;
    }
}
