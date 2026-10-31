package com.norule.musicbot.domain.music;

import java.util.Locale;

public enum MusicPanelMode {
    MODERN, LEGACY;

    public static MusicPanelMode parse(String value) {
        if (value == null || value.isBlank()) {
            return MODERN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MODERN;
        }
    }

    public MusicPanelMode effective(boolean configuredChannelAvailable) {
        return this == MODERN && configuredChannelAvailable ? MODERN : LEGACY;
    }
}
