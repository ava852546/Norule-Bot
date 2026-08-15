package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.domain.discord.DiscordEmbedSanitizer;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;

import java.awt.Color;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MusicPanelRenderer {
    private static final int QUEUE_PREVIEW_LIMIT = 5;
    private static final int PROGRESS_SLOTS = 18;

    private final MusicCommandService owner;
    private final MusicPanelStateStore stateStore;

    public MusicPanelRenderer(MusicCommandService owner, MusicPanelStateStore stateStore) {
        this.owner = owner;
        this.stateStore = stateStore;
    }

    public EmbedBuilder panelEmbed(Guild guild, String lang) {
        MusicPlayerService musicService = owner.musicService();
        AudioTrack currentTrack = musicService.getCurrentTrack(guild);
        List<AudioTrack> queue = musicService.getQueueSnapshot(guild);
        String voiceChannel = voiceChannelLine(guild, lang);

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(currentTrack == null ? new Color(99, 110, 114) : new Color(22, 160, 133))
                .setTitle(DiscordEmbedSanitizer.sanitizeTitle(
                        "\uD83C\uDFB5 " + text(lang, "panel_title")
                ));
        MusicPanelStateStore.PanelNotice panelNotice = stateStore.getPanelNotice(
                guild.getIdLong(),
                System.currentTimeMillis()
        );

        if (currentTrack == null) {
            String idleDescription = "\uD83D\uDCA4 **" + text(lang, "panel_idle") + "**"
                    + "\n\n" + text(lang, "panel_idle_prompt")
                    + "\n\n" + voiceChannel;
            builder
                    .setDescription(DiscordEmbedSanitizer.sanitizeDescription(idleDescription))
                    .addField(
                            DiscordEmbedSanitizer.sanitizeFieldName("\uD83D\uDCC3 " + text(lang, "panel_next")),
                            DiscordEmbedSanitizer.sanitizeFieldValue(text(lang, "panel_queue_empty")),
                            false
                    );
            addPanelNotice(builder, lang, panelNotice);
            return builder;
        }

        String title = safe(currentTrack.getInfo().title, 180);
        String author = safe(currentTrack.getInfo().author, 80);
        String source = displaySource(musicService.getCurrentSource(guild));
        boolean live = musicService.isCurrentStream(guild);
        long duration = musicService.getCurrentDurationMillis(guild);
        long position = musicService.getCurrentPositionMillis(guild);
        String durationText = live ? "\uD83D\uDD34 " + text(lang, "panel_live") : formatDuration(duration);
        String requesterLine = requesterLine(guild, lang, musicService.getCurrentSource(guild));
        String nowPlaying = linkedTitle(title, musicService.getCurrentUri(guild));
        String metadata = author + " \u2022 " + source + " \u2022 " + durationText;
        String description = "\uD83C\uDFA7 **" + text(lang, "panel_current") + "**"
                + "\n\n" + nowPlaying
                + "\n" + metadata
                + "\n" + requesterLine
                + "\n\n" + buildProgress(position, duration, live)
                + "\n\n" + statusLine(guild, lang, queue.size())
                + "\n" + voiceChannel;

        builder.setDescription(DiscordEmbedSanitizer.sanitizeDescription(description))
                .addField(
                        DiscordEmbedSanitizer.sanitizeFieldName("\uD83D\uDCC3 " + text(lang, "panel_next")),
                        DiscordEmbedSanitizer.sanitizeFieldValue(formatQueue(lang, queue)),
                        false
                );

        String autoplayNotice = musicService.getAutoplayNotice(guild.getIdLong());
        if (autoplayNotice != null && !autoplayNotice.isBlank()) {
            builder.addField(
                    DiscordEmbedSanitizer.sanitizeFieldName(text(lang, "panel_autoplay_notice")),
                    DiscordEmbedSanitizer.sanitizeFieldValue(formatAutoplayNotice(lang, autoplayNotice)),
                    false
            );
        }

        addPanelNotice(builder, lang, panelNotice);

        String artwork = musicService.getCurrentArtworkUrl(guild);
        if (isSafeHttpUrl(artwork)) {
            builder.setThumbnail(artwork);
        }
        return builder;
    }

    private void addPanelNotice(EmbedBuilder builder, String lang, MusicPanelStateStore.PanelNotice panelNotice) {
        if (panelNotice == null) {
            return;
        }
        builder.addField(
                DiscordEmbedSanitizer.sanitizeFieldName(text(lang, "panel_playback_error")),
                DiscordEmbedSanitizer.sanitizeFieldValue(panelNotice.message()),
                false
        );
    }

    public List<Button> panelButtons(String lang, long guildId) {
        MusicPlayerService musicService = owner.musicService();
        Guild guild = owner.currentJda() == null ? null : owner.currentJda().getGuildById(guildId);
        boolean connected = guild != null && guild.getAudioManager().getConnectedChannel() != null;
        boolean hasTrack = guild != null && musicService.getCurrentTitle(guild) != null;
        boolean paused = hasTrack && musicService.isPaused(guild);
        int currentVolume = guild == null ? 100 : musicService.getVolume(guild);

        Button playPause = Button.primary(
                MusicCommandService.PANEL_PLAY_PAUSE,
                paused
                        ? "\u25B6 " + text(lang, "btn_play")
                        : "\u23F8 " + text(lang, "btn_pause")
        );
        Button skip = Button.primary(MusicCommandService.PANEL_SKIP, "\u23ED " + text(lang, "btn_skip"));
        Button stop = Button.danger(MusicCommandService.PANEL_STOP, "\u23F9 " + text(lang, "btn_stop"));
        Button leave = Button.secondary(MusicCommandService.PANEL_LEAVE, "\uD83D\uDEAA " + text(lang, "btn_leave"));

        if (!connected || !hasTrack) {
            playPause = playPause.asDisabled();
            skip = skip.asDisabled();
            stop = stop.asDisabled();
        }
        if (!connected) {
            leave = leave.asDisabled();
        }

        Button volumeDown = Button.secondary(
                MusicCommandService.PANEL_VOLUME_DOWN,
                "\uD83D\uDD09 " + text(lang, "btn_volume_down_10")
        );
        Button volumeUp = Button.secondary(
                MusicCommandService.PANEL_VOLUME_UP,
                "\uD83D\uDD0A " + text(lang, "btn_volume_up_10")
        );
        if (!connected || currentVolume <= 1) {
            volumeDown = volumeDown.asDisabled();
        }
        if (!connected || currentVolume >= 100) {
            volumeUp = volumeUp.asDisabled();
        }

        Button repeat = repeatToggleButton(lang, musicService.getRepeatModeByGuildId(guildId));
        Button autoplay = isAutoplayEnabled(guildId)
                ? Button.success(MusicCommandService.PANEL_AUTOPLAY_TOGGLE,
                "\uD83E\uDDE0 " + text(lang, "btn_autoplay_on"))
                : Button.secondary(MusicCommandService.PANEL_AUTOPLAY_TOGGLE,
                "\uD83E\uDDE0 " + text(lang, "btn_autoplay_off"));
        if (!connected) {
            repeat = repeat.asDisabled();
            autoplay = autoplay.asDisabled();
        }

        return List.of(playPause, skip, stop, leave, volumeDown, volumeUp, repeat, autoplay);
    }

    public Button repeatToggleButton(String lang, String repeatMode) {
        String mode = repeatMode == null ? "OFF" : repeatMode.toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "SINGLE" -> Button.success(
                    MusicCommandService.PANEL_REPEAT_TOGGLE,
                    "\uD83D\uDD02 " + text(lang, "btn_repeat_single")
            );
            case "ALL" -> Button.success(
                    MusicCommandService.PANEL_REPEAT_TOGGLE,
                    "\uD83D\uDD01 " + text(lang, "btn_repeat_all")
            );
            default -> Button.secondary(
                    MusicCommandService.PANEL_REPEAT_TOGGLE,
                    "\uD83D\uDD01 " + text(lang, "btn_repeat_off")
            );
        };
    }

    public List<ActionRow> panelRows(String lang, long guildId) {
        List<Button> buttons = panelButtons(lang, guildId);
        return List.of(
                ActionRow.of(buttons.subList(0, 4)),
                ActionRow.of(buttons.subList(4, 8))
        );
    }

    private String requesterLine(Guild guild, String lang, String source) {
        if ("autoplay".equalsIgnoreCase(source)) {
            return "\uD83E\uDDE0 " + text(lang, "panel_autoplay_requester");
        }
        String requester = owner.musicService().getCurrentRequesterDisplay(guild);
        return "\uD83D\uDC64 " + text(lang, "panel_requested_by", Map.of("user", requester));
    }

    private String statusLine(Guild guild, String lang, int queueSize) {
        MusicPlayerService musicService = owner.musicService();
        String state = musicService.isPaused(guild)
                ? "\u23F8 " + text(lang, "panel_paused")
                : "\u25B6 " + text(lang, "panel_playing");
        String autoplay = isAutoplayEnabled(guild.getIdLong())
                ? text(lang, "autoplay_on")
                : text(lang, "autoplay_off");
        return state
                + "\u3000\uD83D\uDD0A " + musicService.getVolume(guild) + "%"
                + "\u3000\uD83D\uDD01 " + owner.repeatLabel(lang, musicService.getRepeatMode(guild))
                + "\u3000\uD83E\uDDE0 " + autoplay
                + "\n\uD83D\uDCC3 " + text(lang, "panel_queue_count", Map.of("count", String.valueOf(queueSize)));
    }

    private String voiceChannelLine(Guild guild, String lang) {
        return guild.getAudioManager().getConnectedChannel() == null
                ? "\uD83D\uDD07 " + text(lang, "panel_not_connected")
                : "\uD83D\uDD0A " + guild.getAudioManager().getConnectedChannel().getAsMention();
    }

    private String formatQueue(String lang, List<AudioTrack> queue) {
        if (queue.isEmpty()) {
            return text(lang, "panel_queue_empty");
        }
        int max = Math.min(queue.size(), QUEUE_PREVIEW_LIMIT);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < max; index++) {
            AudioTrack track = queue.get(index);
            result.append(index + 1)
                    .append(". ")
                    .append(safe(track.getInfo().title, 72));
            String author = safe(track.getInfo().author, 45);
            if (!"-".equals(author)) {
                result.append(" \u2014 ").append(author);
            }
            result.append('\n');
        }
        if (queue.size() > max) {
            result.append('\n').append(text(
                    lang,
                    "panel_more_tracks",
                    Map.of("count", String.valueOf(queue.size() - max))
            ));
        }
        return result.toString().stripTrailing();
    }

    static String buildProgress(long positionMillis, long durationMillis, boolean live) {
        if (live) {
            return "\uD83D\uDD34 LIVE";
        }
        if (durationMillis <= 0L) {
            return formatDuration(positionMillis) + "  --:--";
        }
        long clampedPosition = Math.max(0L, Math.min(positionMillis, durationMillis));
        double ratio = (double) clampedPosition / (double) durationMillis;
        int marker = (int) Math.round(ratio * (PROGRESS_SLOTS - 1));
        StringBuilder bar = new StringBuilder(PROGRESS_SLOTS);
        for (int index = 0; index < PROGRESS_SLOTS; index++) {
            bar.append(index == marker ? '\u25CF' : '\u2501');
        }
        return formatDuration(clampedPosition) + " " + bar + " " + formatDuration(durationMillis);
    }

    private String linkedTitle(String title, String url) {
        if (!isSafeHttpUrl(url)) {
            return "**" + title + "**";
        }
        String escapedTitle = title.replace("[", "\\[").replace("]", "\\]");
        return "[**" + escapedTitle + "**](" + url.replace(")", "%29") + ")";
    }

    static String displaySource(String source) {
        if (source == null || source.isBlank()) {
            return "Unknown";
        }
        return switch (source.trim().toLowerCase(Locale.ROOT)) {
            case "youtube", "youtube-source", "ytsearch" -> "YouTube";
            case "spotify" -> "Spotify";
            case "soundcloud" -> "SoundCloud";
            case "bandcamp" -> "Bandcamp";
            case "twitch", "twitch.tv", "www.twitch.tv" -> "Twitch";
            case "bilibili" -> "Bilibili";
            case "http", "https", "url" -> "HTTP";
            case "autoplay" -> "YouTube";
            default -> "Unknown";
        };
    }

    static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "--:--";
        }
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private String formatAutoplayNotice(String lang, String notice) {
        if ("NO_MATCH".equalsIgnoreCase(notice)) {
            return owner.i18nService().t(lang, "music.autoplay_notice_no_match");
        }
        if (notice.startsWith("LOAD_FAILED:")) {
            return owner.i18nService().t(lang, "music.autoplay_notice_load_failed",
                    Map.of("error", safe(owner.mapMusicLoadError(
                            lang,
                            notice.substring("LOAD_FAILED:".length())
                    ), 140)));
        }
        return safe(notice, 160);
    }

    private boolean isAutoplayEnabled(long guildId) {
        return owner.settingsService().getMusic(guildId).isAutoplayEnabled();
    }

    private boolean isSafeHttpUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2_000) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String text(String lang, String key) {
        return owner.musicText(lang, key);
    }

    private String text(String lang, String key, Map<String, String> placeholders) {
        return owner.musicText(lang, key, placeholders);
    }

    private String safe(String value, int max) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return DiscordEmbedSanitizer.truncate(value, max);
    }
}
