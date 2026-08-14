package io.github.yu1sh.reality.npcs;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Client presentation of the server-provided read-only guide snapshot. */
final class NpcGuideScreen extends Screen implements MenuAccess<NpcGuideMenu> {
    private final NpcGuideMenu menu;
    private NpcGuideSnapshot snapshot;

    NpcGuideScreen(
            NpcGuideMenu menu,
            net.minecraft.world.entity.player.Inventory inventory,
            Component title) {
        super(title);
        this.menu = menu;
        this.snapshot = NpcGuideSnapshot.empty(menu.sessionId());
    }

    @Override
    public NpcGuideMenu getMenu() {
        return menu;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(
                        Component.translatable("reality_npcs.guide.close"),
                        button -> onClose())
                .bounds(this.width / 2 - 40, this.height - 32, 80, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, snapshot.title(), this.width / 2, 18, 0xFFFFFF);
        int left = 16;
        int top = 52;
        int width = Math.max(100, this.width - 32);
        List<FormattedCharSequence> lines = this.font.split(snapshot.body(), width);
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(this.font, lines.get(index), left, top + index * 12, 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    void applySnapshot(NpcGuideSnapshot nextSnapshot) {
        if (nextSnapshot.sessionId() == menu.sessionId()) {
            snapshot = nextSnapshot;
            menu.applySnapshot(nextSnapshot);
        }
    }
}
