package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.domain.music.MusicDataService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;

public class MusicStatsCommandHandler {
    private static final String KEY_UNKNOWN_COMMAND = "general.unknown_command";

    private final MusicCommandService owner;

    public MusicStatsCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handleMusicSlash(SlashCommandInteractionEvent event, String lang) {
        String sub = owner.canonicalMusicSubcommand(event.getSubcommandName());
        if (sub == null || sub.isBlank()) {
            event.replyEmbeds(musicStatsEmbed(event.getGuild(), lang).build()).queue();
            return;
        }
        if ("stats".equals(sub)) {
            event.replyEmbeds(musicStatsEmbed(event.getGuild(), lang).build()).queue();
            return;
        }
        event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
    }

    public EmbedBuilder musicStatsEmbed(Guild guild, String lang) {
        MusicDataService.MusicStatsSnapshot stats = owner.musicService().getStats(guild.getIdLong());
        String topSong = stats.topSongLabel() == null || stats.topSongLabel().isBlank()
                ? owner.musicText(lang, "stats_none")
                : owner.limitText(stats.topSongLabel(), 100) + " (`" + stats.topSongCount() + "`)";
        String topRequester = stats.topRequesterId() == null
                ? owner.musicText(lang, "stats_none")
                : "<@" + stats.topRequesterId() + "> (`" + stats.topRequesterCount() + "`)";
        return new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle("📊 " + owner.musicText(lang, "stats_title"))
                .setDescription(owner.musicText(lang, "stats_desc"))
                .addField("🎵 " + owner.musicText(lang, "stats_top_song"), topSong, false)
                .addField("👤 " + owner.musicText(lang, "stats_top_user"), topRequester, false)
                .addField("⏱️ " + owner.musicText(lang, "stats_today_time"), formatDuration(stats.todayPlaybackMillis()), true)
                .addField("🗒️ " + owner.musicText(lang, "stats_history_count"), String.valueOf(stats.historyCount()), true)
                .setTimestamp(Instant.now());
    }

    private String formatDuration(long millis) {
        if (millis <= 0) {
            return "00:00";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}
