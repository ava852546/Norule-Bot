package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.gateway.command.routing.DiscordCommandRouteMapper;
import com.norule.musicbot.domain.music.MusicDataService;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.norule.musicbot.i18n.I18nService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.function.Supplier;

public class MusicStatsCommandHandler {
    private static final String KEY_UNKNOWN_COMMAND = "general.unknown_command";

    private final MusicTextResolver textResolver;
    private final Supplier<I18nService> i18n;
    private final MusicPlayerService musicService;
    private final DiscordCommandRouteMapper routeMapper = new DiscordCommandRouteMapper();

    public MusicStatsCommandHandler(MusicTextResolver textResolver, Supplier<I18nService> i18n, MusicPlayerService musicService) {
        this.textResolver = textResolver;
        this.i18n = i18n;
        this.musicService = musicService;
    }

    private String musicText(String lang, String key) {
        return textResolver.musicText(lang, key);
    }

    private static String limitText(String value, int max) {
        if (value == null || value.isBlank()) return "-";
        return value.length() <= max ? value : value.substring(0, max - 1);
    }

    public void handleMusicSlash(SlashCommandInteractionEvent event, String lang) {
        String sub = routeMapper.canonicalMusicSubcommand(event.getSubcommandName());
        if (sub == null || sub.isBlank()) {
            event.replyEmbeds(musicStatsEmbed(event.getGuild(), lang).build()).queue();
            return;
        }
        if ("stats".equals(sub)) {
            event.replyEmbeds(musicStatsEmbed(event.getGuild(), lang).build()).queue();
            return;
        }
        event.reply(i18n.get().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
    }

    public EmbedBuilder musicStatsEmbed(Guild guild, String lang) {
        MusicDataService.MusicStatsSnapshot stats = musicService.getStats(guild.getIdLong());
        String topSong = stats.topSongLabel() == null || stats.topSongLabel().isBlank()
                ? musicText(lang, "stats_none")
                : limitText(stats.topSongLabel(), 100) + " (`" + stats.topSongCount() + "`)";
        String topRequester = stats.topRequesterId() == null
                ? musicText(lang, "stats_none")
                : "<@" + stats.topRequesterId() + "> (`" + stats.topRequesterCount() + "`)";
        return new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle("📊 " + musicText(lang, "stats_title"))
                .setDescription(musicText(lang, "stats_desc"))
                .addField("🎵 " + musicText(lang, "stats_top_song"), topSong, false)
                .addField("👤 " + musicText(lang, "stats_top_user"), topRequester, false)
                .addField("⏱️ " + musicText(lang, "stats_today_time"), formatDuration(stats.todayPlaybackMillis()), true)
                .addField("🗒️ " + musicText(lang, "stats_history_count"), String.valueOf(stats.historyCount()), true)
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
