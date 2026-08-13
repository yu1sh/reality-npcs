package io.github.yu1sh.reality.npcs;

import java.util.List;

/** Immutable server-produced view of the persisted guide records. */
final class NpcAdminSnapshot {
    private final long sessionId;
    private final long revision;
    private final List<Entry> entries;

    NpcAdminSnapshot(long sessionId, long revision, List<Entry> entries) {
        this.sessionId = sessionId;
        this.revision = revision;
        this.entries = List.copyOf(entries);
    }

    static NpcAdminSnapshot empty(long sessionId) {
        return new NpcAdminSnapshot(sessionId, -1L, List.of());
    }

    long sessionId() {
        return sessionId;
    }

    long revision() {
        return revision;
    }

    List<Entry> entries() {
        return entries;
    }

    record Entry(
            String stableId,
            boolean enabled,
            String dimension,
            int anchorX,
            int anchorY,
            int anchorZ,
            String entityUuid,
            boolean entityPresent) {
    }
}
