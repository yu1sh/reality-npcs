package io.github.yu1sh.reality.npcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Append-only operational audit for administrative NPC commands. */
final class AuditLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("reality_npcs_audit");

    private AuditLog() {
    }

    static boolean prepare(MinecraftServer server) {
        Path path = path(server);
        try {
            Files.createDirectories(path.getParent());
            try (var ignored = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE)) {
                // Opening in APPEND mode is the writable preflight. No existing byte is replaced.
            }
            return true;
        } catch (IOException exception) {
            LOGGER.error("reality-npcs audit log is not writable at {}", path, exception);
            return false;
        }
    }

    static boolean append(
            MinecraftServer server,
            String actor,
            String action,
            String stableId,
            String dimension,
            String reason,
            String result) {
        Path path = path(server);
        String line = "{"
                + "\"timestamp\":" + quote(Instant.now().toString())
                + ",\"actor\":" + quote(actor)
                + ",\"action\":" + quote(action)
                + ",\"stable_id\":" + nullable(stableId)
                + ",\"world\":" + nullable(dimension)
                + ",\"dimension\":" + nullable(dimension)
                + ",\"reason\":" + quote(reason)
                + ",\"result\":" + quote(result)
                + "}\n";
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
            return true;
        } catch (IOException exception) {
            LOGGER.error("failed to append reality-npcs audit record at {}", path, exception);
            return false;
        }
    }

    private static Path path(MinecraftServer server) {
        return server.getServerDirectory().toPath()
                .resolve("logs")
                .resolve("reality_npcs")
                .resolve("audit.jsonl");
    }

    private static String nullable(String value) {
        return value == null ? "null" : quote(value);
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
