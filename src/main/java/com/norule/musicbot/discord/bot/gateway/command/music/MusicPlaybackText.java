package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.domain.music.YoutubePlaybackErrorMapper;
import com.norule.musicbot.i18n.I18nService;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.Objects;
import java.util.function.Supplier;

public final class MusicPlaybackText {
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
        if ("SPOTIFY_RATE_LIMITED".equalsIgnoreCase(rawError)
                || "SPOTIFY_PLAYLIST_COOLDOWN".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_rate_limited");
        }
        if ("SPOTIFY_PERSONAL_PLAYLIST_UNSUPPORTED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_personal_playlist_unsupported");
        }
        if ("SPOTIFY_JAM_UNSUPPORTED".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_jam_unsupported");
        }
        if ("SPOTIFY_UNSUPPORTED_LINK".equalsIgnoreCase(rawError)) {
            return i18n().t(lang, "music.spotify_unsupported_link");
        }
        return i18n().t(lang, YoutubePlaybackErrorMapper.toMessageKey(rawError));
    }

    private I18nService i18n() {
        return i18nSupplier.get();
    }
}
