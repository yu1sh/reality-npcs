package io.github.yu1sh.reality.npcs;

import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Server-owned menu session; it contains no client-owned authoritative state. */
final class NpcAdminMenu extends AbstractContainerMenu {
    private final long sessionId;
    private long lastRequestId = -1L;
    private long snapshotRevision = -1L;
    private List<NpcAdminSnapshot.Entry> entries = List.of();

    NpcAdminMenu(int containerId, Inventory inventory, long sessionId) {
        super(NpcMenus.NPC_ADMIN.get(), containerId);
        this.sessionId = sessionId;
    }

    long sessionId() {
        return sessionId;
    }

    long snapshotRevision() {
        return snapshotRevision;
    }

    List<NpcAdminSnapshot.Entry> entries() {
        return entries;
    }

    void applySnapshot(NpcAdminSnapshot snapshot) {
        if (snapshot.sessionId() != sessionId) {
            return;
        }
        snapshotRevision = snapshot.revision();
        entries = snapshot.entries();
    }

    long nextRequestId() {
        long requestId = lastRequestId + 1L;
        if (requestId < 0L) {
            requestId = 0L;
        }
        lastRequestId = requestId;
        return requestId;
    }

    boolean acceptRequestId(long requestId) {
        if (requestId < 0L || requestId <= lastRequestId) {
            return false;
        }
        lastRequestId = requestId;
        return true;
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
