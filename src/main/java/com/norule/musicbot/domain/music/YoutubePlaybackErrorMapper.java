package com.norule.musicbot.domain.music;

import java.util.Locale;

public final class YoutubePlaybackErrorMapper {
    private YoutubePlaybackErrorMapper() {
    }

    public static String toMessageKey(String rawError) {
        String error = rawError == null ? "" : rawError.trim();
        if (error.isBlank() || "-".equals(error)) {
            return "music.load_failed_generic";
        }

        String normalized = error.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "members-only",
                "members only",
                "channel members",
                "join this channel",
                "become a member")) {
            return "music.youtube_members_only";
        }
        if (containsAny(normalized,
                "live stream offline",
                "stream is offline",
                "live event will begin",
                "this live event will begin in",
                "this live stream recording is not available")) {
            return "music.youtube_live_not_started";
        }
        if (containsAny(normalized,
                "premieres in",
                "premiere will begin shortly",
                "this premiere will begin in",
                "video is upcoming")) {
            return "music.youtube_premiere_not_started";
        }
        if (containsAny(normalized,
                "video has been removed",
                "this video has been removed",
                "video removed by the uploader",
                "removed by uploader")) {
            return "music.youtube_removed";
        }
        if (containsAny(normalized,
                "confirm your age",
                "age-restricted",
                "age restricted",
                "inappropriate for some users")) {
            return "music.youtube_age_restricted";
        }
        if (containsAny(normalized,
                "private video",
                "video is private",
                "this is a private video")) {
            return "music.youtube_private_video";
        }
        if (containsAny(normalized,
                "not available in your country",
                "not available in your region",
                "blocked in your country",
                "geo-restricted",
                "geo restricted")) {
            return "music.youtube_region_restricted";
        }
        if (normalized.contains("requires login")) {
            return "music.youtube_login_required";
        }
        if (containsAny(normalized,
                "connection refused",
                "connect to 127.0.0.1:8001",
                "connect to localhost:8001",
                ":8001 failed")) {
            return "music.youtube_cipher_unreachable";
        }
        if (containsAny(normalized,
                "requires payment to watch",
                "requires payment")) {
            return "music.youtube_payment_required";
        }
        if (containsAny(normalized,
                "video is not available",
                "this video is unavailable",
                "video unavailable")) {
            return "music.youtube_unavailable";
        }
        if (containsAny(normalized,
                "must find sig function",
                "player script",
                "player configuration error",
                "all clients failed to load the item",
                "signature cipher")) {
            return "music.youtube_source_temporary_failure";
        }
        return "music.load_failed_generic";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
