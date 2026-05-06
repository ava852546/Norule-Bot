package com.norule.musicbot.domain.minecraft;

import java.util.Locale;

public record MinecraftServerAddress(String value) {
    public static MinecraftServerAddress of(String raw) {
        return new MinecraftServerAddress(raw == null ? "" : raw.trim());
    }

    public boolean isValid() {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return false;
        }
        if (value.contains(" ") || value.contains("/") || value.contains("?") || value.contains("#")) {
            return false;
        }
        return true;
    }
}
