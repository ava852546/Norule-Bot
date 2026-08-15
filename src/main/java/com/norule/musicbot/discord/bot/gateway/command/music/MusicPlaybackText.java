package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.domain.music.YoutubePlaybackErrorMapper;
import com.norule.musicbot.i18n.I18nService;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.Objects;
import java.util.function.Supplier;

public final class MusicPlaybackText {
    private static final String SPOTIFY_GENERATED_PLAYLIST_UNAVAILABLE_KEY =
            "music.spotify_generated_playlist_unavailable";
    private final Supplier<I18nService> i18nSupplier;

    public MusicPlaybackText(Supplier<I18nService> i18nSupplier) {
        this.i18nSupplier = Objects.requireNonNull(i18nSupplier, "i18nSupplier");
    }

    public String detectSource(AudioTrack track) {
        String uri = track.getInfo().uri == null ? "" : track.getInfo().uri.toLowerCase();
        if (uri.contains("spotify")) {
            return "spotify";
        }
        if (uri.contains("youtube") || uri.contains("youtu.be")) {
            return "youtube";
        }
        if (uri.contains("soundcloud.com")) {
            return "soundcloud";
        }
        if (uri.contains("bilibili.com") || uri.contains("b23.tv")) {
            return "bilibili";
        }
        return "url";
    }

    public String mapRepeatLabel(String lang, String mode) {
        String normalized = mode == null ? "OFF" : mode.toUpperCase();
        return switch (normalized) {
            case "SINGLE" -> i18n().t(lang, "music.repeat_single");
            case "ALL" -> i18n().t(lang, "music.repeat_all");
            default -> i18n().t(lang, "music.repeat_off");
        };
    }

    public String mapMusicLoadError(String lang, String rawError) {
        if ("SPOTIFY_GENERATED_PLAYLIST_UNAVAILABLE".equalsIgnoreCase(rawError)
                || "AUDIO_SPOTIFY_GENERATED_PLAYLIST_UNAVAILABLE".equalsIgnoreCase(rawError)) {
            return spotifyGeneratedPlaylistUnavailable(lang);
        }
        if ("SPOTIFY_RATE_LIMITED".equalsIgnoreCase(rawError)
                || "SPOTIFY_PLAYLIST_COOLDOWN".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_playlist_rate_limited");
        }
        if ("SPOTIFY_RESTRICTED_OR_PERSONALIZED".equalsIgnoreCase(rawError)
                || "SPOTIFY_PERSONAL_PLAYLIST_UNSUPPORTED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_playlist_restricted");
        }
        if ("SPOTIFY_PLAYLIST_EMPTY".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_playlist_empty");
        }
        if ("SPOTIFY_AUTH_FAILED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_playlist_auth_failed");
        }
        if ("SPOTIFY_JAM_UNSUPPORTED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_jam_unsupported");
        }
        if ("SPOTIFY_UNSUPPORTED_LINK".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_unsupported_link");
        }
        if ("SPOTIFY_SHOW_UNSUPPORTED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_show_unsupported");
        }
        if ("SPOTIFY_EPISODE_UNSUPPORTED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_episode_unsupported");
        }
        if ("YOUTUBE_PRECHECK_BLOCKED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.youtube_precheck_blocked");
        }
        if ("YOUTUBE_PRECHECK_TIMEOUT".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.youtube_precheck_timeout");
        }
        if ("YOUTUBE_PRECHECK_UNAVAILABLE".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.youtube_precheck_unavailable");
        }
        if ("YOUTUBE_PRECHECK_INVALID".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.youtube_precheck_invalid");
        }
        if ("YOUTUBE_PRECHECK_UNKNOWN".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.youtube_precheck_unknown");
        }
        if (rawError != null && rawError.regionMatches(true, 0, "YOUTUBE_", 0, "YOUTUBE_".length())) {
            return i18n().t(lang, switch (rawError.toUpperCase()) {
                case "YOUTUBE_BOT_DETECTED" -> "music.youtube_bot_detected";
                case "YOUTUBE_LOGIN_REQUIRED" -> "music.youtube_login_required";
                case "YOUTUBE_NO_SUPPORTED_AUDIO_STREAM" -> "music.youtube_no_supported_audio_stream";
                case "YOUTUBE_PLAYER_CONFIGURATION_ERROR" -> "music.youtube_player_configuration_error";
                case "YOUTUBE_VIDEO_UNAVAILABLE" -> "music.youtube_unavailable";
                case "YOUTUBE_VIDEO_PRIVATE" -> "music.youtube_private_video";
                case "YOUTUBE_VIDEO_AGE_RESTRICTED" -> "music.youtube_age_restricted";
                case "YOUTUBE_REGION_RESTRICTED" -> "music.youtube_region_restricted";
                case "YOUTUBE_DECODER_FAILURE" -> "music.youtube_decoder_failure";
                case "YOUTUBE_HTTP_FORBIDDEN" -> "music.youtube_http_forbidden";
                case "YOUTUBE_COMPANION_UNAVAILABLE" -> "music.youtube_companion_unavailable";
                case "YOUTUBE_COMPANION_TIMEOUT" -> "music.youtube_companion_timeout";
                case "YOUTUBE_COMPANION_STREAM_UNAVAILABLE" -> "music.youtube_companion_stream_unavailable";
                case "YOUTUBE_HTTP_BAD_REQUEST", "YOUTUBE_SIGNATURE_FAILURE", "YOUTUBE_CIPHER_FAILURE",
                        "YOUTUBE_NETWORK_TIMEOUT", "YOUTUBE_NETWORK_IO", "YOUTUBE_ALL_CLIENTS_FAILED",
                        "YOUTUBE_UNKNOWN" -> "music.youtube_source_temporary_failure";
                default -> "music.load_failed_generic";
            });
        }
        if (rawError != null && rawError.regionMatches(true, 0, "AUDIO_", 0, "AUDIO_".length())) {
            return i18n().t(lang, switch (rawError.toUpperCase()) {
                case "AUDIO_INVALID_INPUT" -> "music.audio_invalid_input";
                case "AUDIO_UNSUPPORTED_SOURCE" -> "music.audio_unsupported_source";
                case "AUDIO_DIRECT_HTTP_DISABLED" -> "music.audio_direct_http_disabled";
                case "AUDIO_DNS_FAILURE" -> "music.audio_dns_failure";
                case "AUDIO_CONNECT_TIMEOUT" -> "music.audio_connect_timeout";
                case "AUDIO_READ_TIMEOUT" -> "music.audio_read_timeout";
                case "AUDIO_HTTP_FORBIDDEN" -> "music.audio_http_forbidden";
                case "AUDIO_HTTP_NOT_FOUND" -> "music.audio_http_not_found";
                case "AUDIO_HTTP_SERVER_ERROR" -> "music.audio_http_server_error";
                case "AUDIO_UNSUPPORTED_FORMAT" -> "music.audio_unsupported_format";
                case "AUDIO_SOURCE_RATE_LIMITED" -> "music.audio_source_rate_limited";
                case "AUDIO_TRACK_STUCK" -> "music.audio_track_stuck";
                case "AUDIO_TRACK_RECOVERING" -> "music.audio_track_recovering";
                case "AUDIO_TRACK_RECOVERY_EXHAUSTED" -> "music.audio_track_recovery_exhausted";
                case "AUDIO_TRACK_RECOVERY_FAILED" -> "music.audio_track_recovery_failed";
                default -> "music.load_failed_generic";
            });
        }
        return i18n().t(lang, YoutubePlaybackErrorMapper.toMessageKey(rawError));
    }

    private String spotifyGeneratedPlaylistUnavailable(String lang) {
        I18nService i18n = i18n();
        String translated = i18n.t(lang, SPOTIFY_GENERATED_PLAYLIST_UNAVAILABLE_KEY);
        if (!SPOTIFY_GENERATED_PLAYLIST_UNAVAILABLE_KEY.equals(translated)) {
            return translated;
        }
        return switch (i18n.normalizeLanguage(lang)) {
            case "zh-TW" -> "\u9019\u662f Spotify \u52d5\u614b\u7522\u751f\u6216\u500b\u4eba\u5316\u7684\u64ad\u653e\u6e05\u55ae\uff0c"
                    + "\u76ee\u524d\u7121\u6cd5\u53d6\u5f97\u5176\u4e2d\u7684\u6b4c\u66f2\u3002"
                    + "\u8acb\u5c07\u6b4c\u66f2\u8907\u88fd\u5230\u4f60\u81ea\u5df1\u5efa\u7acb\u7684\u516c\u958b\u64ad\u653e\u6e05\u55ae\u5f8c\u518d\u8a66\u3002";
            case "zh-CN" -> "\u8fd9\u662f Spotify \u52a8\u6001\u751f\u6210\u6216\u4e2a\u6027\u5316\u7684\u64ad\u653e\u5217\u8868\uff0c"
                    + "\u76ee\u524d\u65e0\u6cd5\u83b7\u53d6\u5176\u4e2d\u7684\u6b4c\u66f2\u3002"
                    + "\u8bf7\u5c06\u6b4c\u66f2\u590d\u5236\u5230\u4f60\u81ea\u5df1\u521b\u5efa\u7684\u516c\u5f00\u64ad\u653e\u5217\u8868\u540e\u91cd\u8bd5\u3002";
            default -> "This is a Spotify-generated or personalized playlist, and its tracks cannot currently be "
                    + "accessed. Please copy the tracks to a public playlist you created and try again.";
        };
    }

    private I18nService i18n() {
        return i18nSupplier.get();
    }
}
