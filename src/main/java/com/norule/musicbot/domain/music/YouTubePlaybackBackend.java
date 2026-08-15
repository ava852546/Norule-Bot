package com.norule.musicbot.domain.music;

import java.util.Locale;

public enum YouTubePlaybackBackend {
    YOUTUBE_SOURCE,
    COMPANION;

    public static YouTubePlaybackBackend parse(String value) {
        if (value == null || value.isBlank()) {
            return YOUTUBE_SOURCE;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return YOUTUBE_SOURCE;
        }
    }

    public static boolean isRecognized(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return "YOUTUBE_SOURCE".equals(normalized) || "COMPANION".equals(normalized);
    }
}
