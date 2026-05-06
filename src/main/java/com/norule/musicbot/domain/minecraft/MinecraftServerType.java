package com.norule.musicbot.domain.minecraft;

import java.util.Locale;

public enum MinecraftServerType {
    JAVA,
    BEDROCK;

    public static MinecraftServerType parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return JAVA;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("JAVA".equals(normalized)) {
            return JAVA;
        }
        if ("BEDROCK".equals(normalized)) {
            return BEDROCK;
        }
        return null;
    }
}
