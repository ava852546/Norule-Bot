package com.norule.musicbot.config.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.function.Function;

/** Credential I/O stays at the configuration boundary; errors never include credential content. */
final class DiscordTokenSource {
    static final String MISSING = "[NoRule] Discord Token not found. Please create a 'token' file next to config.yml or set DISCORD_TOKEN.";

    private DiscordTokenSource() {}

    static Path tokenPath(Path config) {
        return config.toAbsolutePath().normalize().resolveSibling("token");
    }

    static String read(Path config, Function<String, String> environment) {
        String env = environment.apply("DISCORD_TOKEN");
        if (env != null && !env.isBlank()) return normalize(env);
        Path file = tokenPath(config);
        if (!Files.exists(file)) throw new IllegalStateException(MISSING);
        try {
            return normalize(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("[NoRule] Cannot read the 'token' file next to config.yml.");
        }
    }

    static String normalize(String raw) {
        String value = (raw.startsWith("\uFEFF") ? raw.substring(1) : raw).strip();
        if (value.isEmpty()) throw new IllegalStateException(MISSING);
        if (value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalStateException("[NoRule] Discord Token must be a single non-empty plain-text line.");
        }
        return value;
    }

    static boolean migrate(Path config, Map<String, Object> root) throws IOException {
        if (!root.containsKey("token")) return false;
        Object old = root.get("token");
        if (old != null && !old.toString().isBlank()) {
            String value = normalize(old.toString());
            Path target = tokenPath(config);
            if (!Files.exists(target)) {
                // CREATE_NEW prevents replacing a credential created concurrently.
                if (Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")) {
                    Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.writeString(target, value, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
                } else {
                    Files.writeString(target, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                }
            }
            if (!value.equals(normalize(Files.readString(target, StandardCharsets.UTF_8)))) {
                throw new IllegalStateException("[NoRule] Token migration stopped: existing 'token' differs. Reconcile credentials manually; config.yml was preserved.");
            }
        }
        root.remove("token");
        return true;
    }
}
