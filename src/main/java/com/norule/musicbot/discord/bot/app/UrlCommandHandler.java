package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.ShortUrlService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;

public final class UrlCommandHandler {
    private final MusicCommandService owner;

    UrlCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handleUrlSlash(SlashCommandInteractionEvent event, String lang) {
        String target = event.getOption("url") == null ? "" : event.getOption("url").getAsString().trim();
        String slug = event.getOption("slug") == null ? "" : event.getOption("slug").getAsString().trim();
        if (target.isBlank()) {
            event.replyEmbeds(errorEmbed(
                            lang,
                            text(lang, "請提供要縮短的網址。", "Please provide a URL to shorten.")
                    ).build())
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ShortUrlService.ShortUrlEntry entry = owner.shortUrlService().create(target, slug);
        if (entry == null) {
            event.replyEmbeds(errorEmbed(
                            lang,
                            text(lang,
                                    "網址或自訂代碼無效。請確認網址以 http:// 或 https:// 開頭、代碼可用，且不能縮短本站短網址。",
                                    "Invalid URL or custom slug. Ensure URL starts with http:// or https://, slug is available, and you cannot shorten this short-link domain.")
                    ).build())
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String shortUrl = owner.shortUrlService().toPublicUrl(entry.getCode());
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(46, 204, 113))
                .setTitle(text(lang, "短網址建立成功", "Short URL Created"))
                .addField(text(lang, "短網址", "Short URL"), shortUrl, false)
                .addField(text(lang, "目標網址", "Target URL"), entry.getTarget(), false)
                .setTimestamp(Instant.now());
        event.replyEmbeds(embed.build()).queue();
    }

    private EmbedBuilder errorEmbed(String lang, String description) {
        return new EmbedBuilder()
                .setColor(new Color(231, 76, 60))
                .setTitle(text(lang, "短網址建立失敗", "Short URL Failed"))
                .setDescription(description)
                .setTimestamp(Instant.now());
    }

    private String text(String lang, String zh, String en) {
        return lang != null && lang.toLowerCase().startsWith("zh") ? zh : en;
    }
}
