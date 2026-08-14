package io.github.yu1sh.reality.npcs;

import java.util.Objects;
import net.minecraft.network.chat.Component;

/** Immutable server-produced view for a player's read-only guide screen. */
final class NpcGuideSnapshot {
    private final long sessionId;
    private final Component title;
    private final Component body;

    NpcGuideSnapshot(long sessionId, Component title, Component body) {
        this.sessionId = sessionId;
        this.title = Objects.requireNonNull(title);
        this.body = Objects.requireNonNull(body);
    }

    static NpcGuideSnapshot empty(long sessionId) {
        return new NpcGuideSnapshot(
                sessionId,
                Component.empty(),
                Component.empty());
    }

    long sessionId() {
        return sessionId;
    }

    Component title() {
        return title;
    }

    Component body() {
        return body;
    }
}
