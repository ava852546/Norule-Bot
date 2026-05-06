package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.domain.minecraft.MinecraftServerStatus;
import com.norule.musicbot.ops.minecraft.MinecraftStatusOps;
import com.norule.musicbot.service.minecraft.MinecraftStatusService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;

public final class MinecraftStatusCommandHandler {
    private final MusicCommandService owner;
    private final MinecraftStatusOps minecraftStatusOps;

    MinecraftStatusCommandHandler(MusicCommandService owner, MinecraftStatusOps minecraftStatusOps) {
        this.owner = owner;
        this.minecraftStatusOps = minecraftStatusOps;
    }

    public void handleStatusSlash(SlashCommandInteractionEvent event, String lang) {
        event.deferReply().queue(
                success -> handleStatusSlashDeferred(event, lang),
                failure -> {
                }
        );
    }

    private void handleStatusSlashDeferred(SlashCommandInteractionEvent event, String lang) {
        String address = event.getOption("address") == null ? "" : event.getOption("address").getAsString().trim();
        String type = event.getOption("type") == null ? "JAVA" : event.getOption("type").getAsString().trim();
        MinecraftStatusService.QueryResult result = minecraftStatusOps.query(address, type);
        if (!result.success()) {
            event.getHook().editOriginalEmbeds(errorEmbed(lang, result.errorMessage()).build()).queue();
            return;
        }

        MinecraftServerStatus status = result.status();
        String endpoint = status.ip().isBlank() ? "-" : status.ip() + (status.port() > 0 ? ":" + status.port() : "");
        String players = status.playersMax() > 0
                ? status.playersOnline() + " / " + status.playersMax()
                : String.valueOf(status.playersOnline());
        String motd = status.motd() == null || status.motd().isBlank() ? "-" : owner.limitText(status.motd(), 900);
        String cacheText = status.cached() ? text(lang, "是", "Yes") : text(lang, "否", "No");
        String onlineText = status.online() ? text(lang, "上線", "Online") : text(lang, "離線", "Offline");

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(status.online() ? new Color(46, 204, 113) : new Color(231, 76, 60))
                .setTitle(text(lang, "Minecraft 伺服器狀態", "Minecraft Server Status"))
                .addField(text(lang, "狀態", "Status"), onlineText, true)
                .addField(text(lang, "類型", "Type"), status.serverType().name(), true)
                .addField(text(lang, "快取", "Cached"), cacheText, true)
                .addField(text(lang, "查詢位址", "Address"), status.address(), false)
                .addField(text(lang, "解析結果", "Resolved"), endpoint, true)
                .addField(text(lang, "版本", "Version"), status.version().isBlank() ? "-" : owner.limitText(status.version(), 120), true)
                .addField(text(lang, "玩家", "Players"), players, true)
                .addField("MOTD", motd, false)
                .setFooter(text(lang, "本服務使用 https://mcsrvstat.us/", "Powered by https://mcsrvstat.us/"))
                .setTimestamp(Instant.now());
        if (status.iconUrl() != null && !status.iconUrl().isBlank()) {
            embed.setThumbnail(status.iconUrl());
        }
        event.getHook().editOriginalEmbeds(embed.build()).queue();
    }

    private EmbedBuilder errorEmbed(String lang, String message) {
        return new EmbedBuilder()
                .setColor(new Color(231, 76, 60))
                .setTitle(text(lang, "Minecraft 查詢失敗", "Minecraft Query Failed"))
                .setDescription(owner.limitText((message == null || message.isBlank()) ? text(lang, "請稍後再試。", "Please try again later.") : message, 400))
                .setTimestamp(Instant.now());
    }

    private String text(String lang, String zh, String en) {
        return lang != null && lang.toLowerCase().startsWith("zh") ? zh : en;
    }
}
