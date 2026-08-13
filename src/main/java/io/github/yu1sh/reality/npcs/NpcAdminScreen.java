package io.github.yu1sh.reality.npcs;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;

/** Minimal client presentation for a server-produced administrator snapshot. */
final class NpcAdminScreen extends Screen implements MenuAccess<NpcAdminMenu> {
    private static final int ROW_HEIGHT = 32;
    private static final int LIST_TOP = 44;
    private static final int LIST_BOTTOM_MARGIN = 48;
    private final NpcAdminMenu menu;
    private NpcAdminSnapshot snapshot;
    private String selectedStableId;
    private int scrollOffset;
    private int listLeft;
    private int listRight;
    private int listBottom;
    private Button disableButton;
    private Button deleteButton;
    private Button recreateButton;

    NpcAdminScreen(NpcAdminMenu menu, net.minecraft.world.entity.player.Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        this.snapshot = NpcAdminSnapshot.empty(menu.sessionId());
    }

    @Override
    public NpcAdminMenu getMenu() {
        return menu;
    }

    @Override
    protected void init() {
        int split = Math.max(170, this.width / 2);
        listLeft = 8;
        listRight = split - 8;
        listBottom = this.height - LIST_BOTTOM_MARGIN;

        addRenderableWidget(Button.builder(
                        Component.literal("Spawn guide"),
                        button -> request(NpcAdminOperation.SPAWN, ""))
                .bounds(listLeft, 18, 92, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.literal("Refresh"),
                        button -> request(NpcAdminOperation.REFRESH, ""))
                .bounds(listLeft + 98, 18, 70, 20)
                .build());

        int detailLeft = split + 4;
        disableButton = addRenderableWidget(Button.builder(
                        Component.literal("Disable"),
                        button -> requestSelected(NpcAdminOperation.DISABLE))
                .bounds(detailLeft, this.height - 44, 60, 20)
                .build());
        deleteButton = addRenderableWidget(Button.builder(
                        Component.literal("Delete"),
                        button -> requestSelected(NpcAdminOperation.DELETE))
                .bounds(detailLeft + 64, this.height - 44, 60, 20)
                .build());
        recreateButton = addRenderableWidget(Button.builder(
                        Component.literal("Recreate"),
                        button -> requestSelected(NpcAdminOperation.RECREATE))
                .bounds(detailLeft + 128, this.height - 44, 72, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.literal("Close"),
                        button -> onClose())
                .bounds(this.width - 68, this.height - 24, 60, 20)
                .build());
        updateActionButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int split = Math.max(170, this.width / 2);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFF);
        drawString(graphics, "Guide records", listLeft, 34, 0xFFFFFF);
        drawString(graphics, "Details", split + 4, 34, 0xFFFFFF);
        graphics.fill(listLeft, LIST_TOP, listRight, listBottom, 0x66000000);
        graphics.fill(split + 2, LIST_TOP, this.width - 8, listBottom, 0x66000000);

        List<NpcAdminSnapshot.Entry> entries = snapshot.entries();
        int visibleRows = visibleRows();
        for (int row = 0; row < visibleRows; row++) {
            int index = scrollOffset + row;
            if (index >= entries.size()) {
                break;
            }
            NpcAdminSnapshot.Entry entry = entries.get(index);
            int y = LIST_TOP + row * ROW_HEIGHT;
            if (entry.stableId().equals(selectedStableId)) {
                graphics.fill(listLeft + 2, y + 2, listRight - 2, y + ROW_HEIGHT - 2, 0x88555555);
            }
            drawString(graphics, entry.stableId(), listLeft + 6, y + 5, 0xFFFFFF);
            drawString(graphics, (entry.enabled() ? "enabled" : "disabled")
                    + (entry.entityPresent() ? " / entity present" : " / entity absent"),
                    listLeft + 6,
                    y + 18,
                    entry.enabled() ? 0xA8FF9E : 0xFFCC66);
        }

        NpcAdminSnapshot.Entry selected = selectedEntry();
        if (selected == null) {
            drawString(graphics, "Select a guide record.", split + 10, LIST_TOP + 10, 0xCCCCCC);
        } else {
            int detailX = split + 10;
            int y = LIST_TOP + 10;
            drawString(graphics, selected.stableId(), detailX, y, 0xFFFFFF);
            drawString(graphics, "state: " + (selected.enabled() ? "enabled" : "disabled"), detailX, y + 20, 0xFFFFFF);
            drawString(graphics, "dimension: " + selected.dimension(), detailX, y + 36, 0xFFFFFF);
            drawString(graphics, "anchor: " + selected.anchorX() + ", "
                    + selected.anchorY() + ", " + selected.anchorZ(), detailX, y + 52, 0xFFFFFF);
            drawString(graphics, "entity UUID: " + selected.entityUuid(), detailX, y + 68, 0xCCCCCC);
            drawString(graphics, "snapshot revision: " + snapshot.revision(), detailX, y + 88, 0xCCCCCC);
            drawString(graphics, "entity present: " + selected.entityPresent(), detailX, y + 104, 0xCCCCCC);
        }
        drawString(graphics, "Scroll the list for more records.", listLeft, listBottom + 8, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0
                && mouseX >= listLeft
                && mouseX < listRight
                && mouseY >= LIST_TOP
                && mouseY < listBottom) {
            int row = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            int index = scrollOffset + row;
            if (index >= 0 && index < snapshot.entries().size()) {
                selectedStableId = snapshot.entries().get(index).stableId();
                updateActionButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= listLeft && mouseX < listRight && mouseY >= LIST_TOP && mouseY < listBottom) {
            int maxOffset = Math.max(0, snapshot.entries().size() - visibleRows());
            scrollOffset = (int) Math.max(0, Math.min(maxOffset, scrollOffset - Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    void applySnapshot(NpcAdminSnapshot nextSnapshot) {
        if (nextSnapshot.sessionId() != menu.sessionId()) {
            return;
        }
        snapshot = nextSnapshot;
        menu.applySnapshot(nextSnapshot);
        if (selectedEntry() == null) {
            selectedStableId = null;
        }
        int maxOffset = Math.max(0, snapshot.entries().size() - visibleRows());
        scrollOffset = Math.min(scrollOffset, maxOffset);
        updateActionButtons();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
    }

    private NpcAdminSnapshot.Entry selectedEntry() {
        if (selectedStableId == null) {
            return null;
        }
        for (NpcAdminSnapshot.Entry entry : snapshot.entries()) {
            if (entry.stableId().equals(selectedStableId)) {
                return entry;
            }
        }
        return null;
    }

    private void updateActionButtons() {
        if (disableButton == null) {
            return;
        }
        NpcAdminSnapshot.Entry selected = selectedEntry();
        boolean hasSelection = selected != null;
        disableButton.active = hasSelection && selected.enabled();
        deleteButton.active = hasSelection;
        recreateButton.active = hasSelection;
    }

    private void requestSelected(NpcAdminOperation operation) {
        NpcAdminSnapshot.Entry selected = selectedEntry();
        if (selected != null) {
            request(operation, selected.stableId());
        }
    }

    private void request(NpcAdminOperation operation, String stableId) {
        NpcNetwork.sendToServer(new NpcNetwork.Request(
                menu.sessionId(),
                menu.nextRequestId(),
                menu.snapshotRevision(),
                operation,
                stableId));
    }

    private void drawString(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(this.font, Component.literal(text), x, y, color);
    }
}
