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
    private static final int SIDE_PADDING = 16;
    private static final int BODY_TOP = 50;
    private static final int LINE_HEIGHT = 12;
    private static final int MAX_PAGE_LINES = 8;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_WIDTH = 80;
    private final NpcGuideMenu menu;
    private NpcGuideSnapshot snapshot;
    private List<FormattedCharSequence> wrappedLines = List.of(FormattedCharSequence.EMPTY);
    private int currentPage;
    private int linesPerPage = 1;
    private int pageCount = 1;
    private Button previousButton;
    private Button nextButton;

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
        rebuildPages();
        int buttonWidth = Math.max(
                1,
                Math.min(
                        BUTTON_WIDTH,
                        (this.width - SIDE_PADDING * 2 - BUTTON_GAP) / 2));
        int navigationWidth = buttonWidth * 2 + BUTTON_GAP;
        int navigationLeft = Math.max(0, (this.width - navigationWidth) / 2);
        previousButton = addRenderableWidget(Button.builder(
                        Component.translatable("reality_npcs.guide.previous"),
                        button -> changePage(-1))
                .bounds(navigationLeft, navigationY(), buttonWidth, BUTTON_HEIGHT)
                .build());
        nextButton = addRenderableWidget(Button.builder(
                        Component.translatable("reality_npcs.guide.next"),
                        button -> changePage(1))
                .bounds(navigationLeft + buttonWidth + BUTTON_GAP, navigationY(), buttonWidth, BUTTON_HEIGHT)
                .build());
        int closeWidth = Math.max(1, Math.min(BUTTON_WIDTH, this.width - SIDE_PADDING * 2));
        addRenderableWidget(Button.builder(
                        Component.translatable("reality_npcs.guide.close"),
                        button -> onClose())
                .bounds(
                        Math.max(0, (this.width - closeWidth) / 2),
                        closeButtonY(),
                        closeWidth,
                        BUTTON_HEIGHT)
                .build());
        updateNavigationButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, snapshot.title(), this.width / 2, 18, 0xFFFFFF);
        int startLine = currentPage * linesPerPage;
        int endLine = Math.min(wrappedLines.size(), startLine + linesPerPage);
        if (startLine >= endLine) {
            startLine = 0;
            endLine = Math.min(wrappedLines.size(), linesPerPage);
            currentPage = 0;
            updateNavigationButtons();
        }
        graphics.drawCenteredString(
                this.font,
                Component.translatable(
                        "reality_npcs.guide.page",
                        currentPage + 1,
                        pageCount,
                        startLine + 1,
                        endLine),
                this.width / 2,
                32,
                0xCCCCCC);

        int left = bodyLeft();
        int bodyBottom = Math.min(this.height, navigationY() - 8);
        for (int lineIndex = startLine; lineIndex < endLine; lineIndex++) {
            int y = bodyTop() + (lineIndex - startLine) * LINE_HEIGHT;
            if (y < 0 || y + LINE_HEIGHT > bodyBottom) {
                break;
            }
            graphics.drawString(this.font, wrappedLines.get(lineIndex), left, y, 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    void applySnapshot(NpcGuideSnapshot nextSnapshot) {
        if (nextSnapshot.sessionId() == menu.sessionId()) {
            snapshot = nextSnapshot;
            menu.applySnapshot(nextSnapshot);
            currentPage = 0;
            rebuildPages();
        }
    }

    private void rebuildPages() {
        List<FormattedCharSequence> lines = this.font.split(snapshot.body(), bodyWidth());
        wrappedLines = lines.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : lines;
        linesPerPage = calculateLinesPerPage();
        pageCount = Math.max(1, (wrappedLines.size() + linesPerPage - 1) / linesPerPage);
        currentPage = Math.min(currentPage, pageCount - 1);
        updateNavigationButtons();
    }

    private int calculateLinesPerPage() {
        int availableHeight = navigationY() - bodyTop() - 8;
        return Math.max(1, Math.min(MAX_PAGE_LINES, availableHeight / LINE_HEIGHT));
    }

    private int bodyLeft() {
        return Math.min(SIDE_PADDING, Math.max(0, (this.width - 1) / 2));
    }

    private int bodyWidth() {
        return Math.max(1, this.width - bodyLeft() * 2);
    }

    private int bodyTop() {
        return Math.min(BODY_TOP, Math.max(0, navigationY() - LINE_HEIGHT - 8));
    }

    private int navigationY() {
        return Math.max(0, Math.min(Math.max(0, this.height - BUTTON_HEIGHT), this.height - 56));
    }

    private int closeButtonY() {
        return Math.max(0, this.height - 32);
    }

    private void changePage(int delta) {
        int nextPage = currentPage + delta;
        if (nextPage < 0 || nextPage >= pageCount) {
            return;
        }
        currentPage = nextPage;
        updateNavigationButtons();
    }

    private void updateNavigationButtons() {
        if (previousButton == null || nextButton == null) {
            return;
        }
        previousButton.active = currentPage > 0;
        nextButton.active = currentPage + 1 < pageCount;
    }
}
