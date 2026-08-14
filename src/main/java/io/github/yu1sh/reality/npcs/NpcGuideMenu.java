package io.github.yu1sh.reality.npcs;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Server-owned read-only menu session for one guide snapshot. */
final class NpcGuideMenu extends AbstractContainerMenu {
    private final long sessionId;
    private NpcGuideSnapshot snapshot;

    NpcGuideMenu(int containerId, Inventory inventory, long sessionId) {
        super(NpcMenus.NPC_GUIDE.get(), containerId);
        this.sessionId = sessionId;
        this.snapshot = NpcGuideSnapshot.empty(sessionId);
    }

    long sessionId() {
        return sessionId;
    }

    Component title() {
        return snapshot.title();
    }

    Component body() {
        return snapshot.body();
    }

    void applySnapshot(NpcGuideSnapshot nextSnapshot) {
        if (nextSnapshot.sessionId() == sessionId) {
            snapshot = nextSnapshot;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && player.containerMenu == this;
    }
}
