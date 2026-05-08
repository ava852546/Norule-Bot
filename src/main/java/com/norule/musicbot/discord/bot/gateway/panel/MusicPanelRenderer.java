package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MusicPanelRenderer {
    private final MusicCommandService owner;

    public MusicPanelRenderer(MusicCommandService owner) {
        this.owner = owner;
    }

    public EmbedBuilder panelEmbed(Guild guild, String lang) {
        long guildId = guild.getIdLong();
        MusicPlayerService musicService = owner.musicService();
        String current = musicService.getCurrentTitle(guild);
        String author = current == null ? owner.musicUx(lang, "panel_none") : safe(musicService.getCurrentAuthor(guild), 80);
        long duration = musicService.getCurrentDurationMillis(guild);
        long position = musicService.getCurrentPositionMillis(guild);
        String progress = current == null ? owner.musicUx(lang, "panel_none") : buildProgressBar(position, duration);
        String requester = current == null ? owner.musicUx(lang, "panel_none") : musicService.getCurrentRequesterDisplay(guild);
        String artwork = musicService.getCurrentArtworkUrl(guild);
        String state = current == null ? owner.musicUx(lang, "panel_idle") : (musicService.isPaused(guild)
                ? owner.musicUx(lang, "panel_paused") : owner.musicUx(lang, "panel_playing"));
        List<AudioTrack> queue = musicService.getQueueSnapshot(guild);
        String queueText = queue.isEmpty() ? owner.musicUx(lang, "panel_none") : formatQueue(queue);
        String connected = guild.getAudioManager().getConnectedChannel() == null
                ? owner.musicUx(lang, "panel_none")
                : "<#" + guild.getAudioManager().getConnectedChannel().getId() + ">";
        String source = musicService.getCurrentSource(guild);
        if (source == null || source.isBlank()) {
            source = owner.musicUx(lang, "panel_none");
        }
        String autoplayState = isAutoplayEnabled(guildId) ? owner.musicUx(lang, "autoplay_on") : owner.musicUx(lang, "autoplay_off");
        String autoplayNotice = musicService.getAutoplayNotice(guildId);
        String currentText = current == null ? owner.musicUx(lang, "panel_none") : ("`" + current + "`");
        String summaryLine = "\u25B6 **" + owner.musicUx(lang, "panel_state") + "**: " + state
                + "  |  \uD83D\uDD01 **" + owner.musicUx(lang, "panel_repeat") + "**: " + owner.repeatLabel(lang, musicService.getRepeatMode(guild))
                + "\n\uD83D\uDD0A **" + owner.musicUx(lang, "panel_channel") + "**: " + connected
                + "  |  \uD83D\uDCCB **" + owner.musicUx(lang, "panel_queue") + "**: `" + queue.size() + "`"
                + "  |  \uD83D\uDD09 **" + owner.musicUx(lang, "panel_volume") + "**: `" + musicService.getVolume(guild) + "%`"
                + "\n\uD83E\uDDE0 **" + owner.musicUx(lang, "panel_autoplay") + "**: " + autoplayState;
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(current == null ? new Color(99, 110, 114) : new Color(22, 160, 133))
                .setTitle("\uD83C\uDFB5 " + owner.musicUx(lang, "panel_title"))
                .setDescription(summaryLine)
                .addField("\uD83C\uDFA7 " + owner.musicUx(lang, "panel_current"), currentText, false)
                .addField("\uD83D\uDC64 " + owner.musicUx(lang, "panel_requester"), requester, true)
                .addField("\uD83C\uDFA4 " + owner.musicUx(lang, "panel_author"), author, true)
                .addField("\uD83D\uDD17 " + owner.musicUx(lang, "panel_source"), source, true)
                .addField("\u23F1\uFE0F " + owner.musicUx(lang, "panel_duration"), current == null ? owner.musicUx(lang, "panel_none") : formatDuration(duration), true)
                .addField("\uD83D\uDCCA " + owner.musicUx(lang, "panel_progress"), progress, false)
                .addField("\uD83D\uDCC3 " + owner.musicUx(lang, "panel_queue"), queueText, false)
                .setFooter("\u21BB " + owner.musicUx(lang, "btn_refresh"))
                .setTimestamp(Instant.now());
        if (autoplayNotice != null && !autoplayNotice.isBlank()) {
            builder.addField(owner.musicUx(lang, "panel_autoplay_notice"), formatAutoplayNotice(lang, autoplayNotice), false);
        }
        if (artwork != null && !artwork.isBlank()) {
            builder.setImage(artwork);
        }
        return builder;
    }

    public List<Button> panelButtons(String lang, long guildId) {
        MusicPlayerService musicService = owner.musicService();
        List<Button> buttons = new ArrayList<>();
        String repeatMode = musicService.getRepeatModeByGuildId(guildId);
        boolean autoplayEnabled = isAutoplayEnabled(guildId);
        JDA currentJda = owner.currentJda();
        Guild guild = currentJda == null ? null : currentJda.getGuildById(guildId);
        int currentVolume = guild == null ? 100 : musicService.getVolume(guild);
        boolean hasQueue = guild != null && !musicService.getQueueSnapshot(guild).isEmpty();
        buttons.add(Button.primary(MusicCommandService.PANEL_PLAY_PAUSE, "\u23EF " + owner.musicUx(lang, "btn_play_pause")));
        buttons.add(Button.primary(MusicCommandService.PANEL_SKIP, "\u23ED " + owner.musicUx(lang, "btn_skip")));
        buttons.add(Button.danger(MusicCommandService.PANEL_STOP, "\u23F9 " + owner.musicUx(lang, "btn_stop")));
        buttons.add(Button.secondary(MusicCommandService.PANEL_LEAVE, "\uD83D\uDCE4 " + owner.musicUx(lang, "btn_leave")));
        buttons.add(currentVolume <= 1
                ? Button.secondary(MusicCommandService.PANEL_VOLUME_DOWN, "\uD83D\uDD09 " + owner.musicUx(lang, "btn_volume_down")).asDisabled()
                : Button.secondary(MusicCommandService.PANEL_VOLUME_DOWN, "\uD83D\uDD09 " + owner.musicUx(lang, "btn_volume_down")));
        buttons.add(currentVolume >= 100
                ? Button.secondary(MusicCommandService.PANEL_VOLUME_UP, "\uD83D\uDD0A " + owner.musicUx(lang, "btn_volume_up")).asDisabled()
                : Button.secondary(MusicCommandService.PANEL_VOLUME_UP, "\uD83D\uDD0A " + owner.musicUx(lang, "btn_volume_up")));
        buttons.add(Button.secondary(MusicCommandService.PANEL_REFRESH, "\uD83D\uDD04 " + owner.musicUx(lang, "btn_refresh")));
        buttons.add(autoplayEnabled
                ? Button.success(MusicCommandService.PANEL_AUTOPLAY_TOGGLE, "\uD83E\uDDE0 " + owner.musicUx(lang, "btn_autoplay_on"))
                : Button.secondary(MusicCommandService.PANEL_AUTOPLAY_TOGGLE, "\uD83E\uDDE0 " + owner.musicUx(lang, "btn_autoplay_off")));
        buttons.add(repeatToggleButton(lang, repeatMode));
        buttons.add(hasQueue
                ? Button.secondary(MusicCommandService.PANEL_SHUFFLE, "\uD83D\uDD00 " + owner.musicUx(lang, "btn_shuffle"))
                : Button.secondary(MusicCommandService.PANEL_SHUFFLE, "\uD83D\uDD00 " + owner.musicUx(lang, "btn_shuffle")).asDisabled());
        return buttons;
    }

    public Button repeatToggleButton(String lang, String repeatMode) {
        String mode = repeatMode == null ? "OFF" : repeatMode.toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "SINGLE" -> Button.success(MusicCommandService.PANEL_REPEAT_TOGGLE, "\uD83D\uDD02 " + owner.musicUx(lang, "btn_repeat_single"));
            case "ALL" -> Button.success(MusicCommandService.PANEL_REPEAT_TOGGLE, "\uD83D\uDD01 " + owner.musicUx(lang, "btn_repeat_all"));
            default -> Button.secondary(MusicCommandService.PANEL_REPEAT_TOGGLE, "\u2B55 " + owner.musicUx(lang, "btn_repeat_off"));
        };
    }

    public List<ActionRow> panelRows(String lang, long guildId) {
        List<Button> buttons = panelButtons(lang, guildId);
        return List.of(
                ActionRow.of(buttons.subList(0, 4)),
                ActionRow.of(buttons.subList(4, 8)),
                ActionRow.of(buttons.subList(8, buttons.size()))
        );
    }

    private boolean isAutoplayEnabled(long guildId) {
        return owner.settingsService().getMusic(guildId).isAutoplayEnabled();
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private String formatQueue(List<AudioTrack> queue) {
        int max = Math.min(queue.size(), 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            AudioTrack track = queue.get(i);
            sb.append(i + 1)
                    .append(". `")
                    .append(safe(track.getInfo().title, 60))
                    .append("` (")
                    .append(formatDuration(track.getDuration()))
                    .append(")\n");
        }
        if (queue.size() > max) {
            sb.append("...");
        }
        return sb.toString();
    }

    private String buildProgressBar(long positionMillis, long durationMillis) {
        if (durationMillis <= 0L) {
            return formatDuration(positionMillis) + " / --:--";
        }
        int totalSlots = 16;
        double ratio = Math.max(0d, Math.min(1d, (double) positionMillis / (double) durationMillis));
        int marker = (int) Math.round(ratio * (totalSlots - 1));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < totalSlots; i++) {
            bar.append(i == marker ? ">" : "=");
        }
        return "[" + bar + "]\n`" + formatDuration(positionMillis) + " / " + formatDuration(durationMillis) + "`";
    }

    private String formatAutoplayNotice(String lang, String notice) {
        if ("NO_MATCH".equalsIgnoreCase(notice)) {
            return owner.i18nService().t(lang, "music.autoplay_notice_no_match");
        }
        if (notice.startsWith("LOAD_FAILED:")) {
            return owner.i18nService().t(lang, "music.autoplay_notice_load_failed",
                    Map.of("error", safe(owner.mapMusicLoadError(lang, notice.substring("LOAD_FAILED:".length())), 140)));
        }
        return safe(notice, 160);
    }

    private String safe(String value, int max) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.length() <= max ? value : value.substring(0, max - 1);
    }
}
