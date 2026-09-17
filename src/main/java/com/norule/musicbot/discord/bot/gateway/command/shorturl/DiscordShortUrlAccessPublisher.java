package com.norule.musicbot.discord.bot.gateway.command.shorturl;

import com.norule.musicbot.domain.shorturl.ShortUrlAccessEvent;
import com.norule.musicbot.shorturl.ShortUrlAccessPublisher;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;
import java.net.URI;
import java.time.Instant;

public final class DiscordShortUrlAccessPublisher implements ShortUrlAccessPublisher {
    private final JDA jda;

    public DiscordShortUrlAccessPublisher(JDA jda) {
        if (jda == null) {
            throw new IllegalArgumentException("jda cannot be null");
        }
        this.jda = jda;
    }

    @Override
    public void publish(long channelId, ShortUrlAccessEvent event) {
        if (event == null || channelId <= 0L) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null || !channel.canTalk()
                || !channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_EMBED_LINKS)) {
            return;
        }
        channel.sendMessageEmbeds(buildEmbed(event).build()).queue(
                ignored -> {
                },
                error -> System.out.println("[NoRule] Short URL Discord log failed: " + error.getMessage())
        );
    }

    private EmbedBuilder buildEmbed(ShortUrlAccessEvent event) {
        boolean viewed = event.action() == ShortUrlAccessEvent.Action.VIEWED;
        boolean image = event.resourceType() == ShortUrlAccessEvent.ResourceType.IMAGE;
        boolean video = event.resourceType() == ShortUrlAccessEvent.ResourceType.VIDEO;
        String resourceName = video ? "影片短網址" : image ? "圖片短網址" : "短網址";
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(viewed ? new Color(52, 152, 219) : new Color(46, 204, 113))
                .setTitle(resourceName + (viewed ? "瀏覽日誌" : "建立日誌"))
                .addField("短網址", event.publicUrl(), false)
                .addField("代碼", '`' + safe(event.code(), 128) + '`', true)
                .addField("累計瀏覽", '`' + String.valueOf(event.viewCount()) + '`', true)
                .addField("到期時間", event.expiresAt() == Long.MAX_VALUE
                        ? "\u7121\u671f\u9650" : "<t:" + event.expiresAt() / 1000L + ":F>", false)
                .setTimestamp(Instant.ofEpochMilli(event.occurredAt()));
        if (image || video) {
            embed.addField("存取方式", event.passwordProtected() ? "密碼保護" : "公開", true)
                    .addField("內容類型", '`' + safe(event.target(), 128) + '`', true);
        } else {
            embed.addField("目標網址", targetOrigin(event.target()), false);
        }
        String creatorDiscordUserId = event.creatorDiscordUserId() == null ? "" : event.creatorDiscordUserId();
        if (!viewed && !creatorDiscordUserId.isBlank()) {
            embed.addField("\u5efa\u7acb\u5e33\u865f", discordMention(creatorDiscordUserId), true);
        }
        if ((image || video) && !viewed && event.fileSizeBytes() > 0L) {
            embed.addField("\u6a94\u6848\u5927\u5c0f", formatFileSize(event.fileSizeBytes()), true);
        }
        String clientAddress = event.clientAddress() == null ? "" : event.clientAddress();
        if (!clientAddress.isBlank()) {
            embed.addField("來源 IP", '`' + safe(clientAddress, 128) + '`', true);
            if (viewed) {
                embed.addField("User-Agent", '`' + safe(event.userAgent(), 900) + '`', false);
            }
        }
        return embed;
    }

    private String discordMention(String userId) {
        String normalized = userId == null ? "" : userId.trim();
        return normalized.matches("\\d{17,20}") ? "<@" + normalized + ">" : safe(normalized, 128);
    }

    static String targetOrigin(String target) {
        try {
            URI uri = URI.create(target);
            if (uri.getHost() == null || uri.getScheme() == null) return "unknown";
            return uri.getScheme() + "://" + uri.getHost();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return "unknown";
        }
    }

    private String formatFileSize(long sizeBytes) {
        if (sizeBytes < 1024L) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", sizeBytes / 1024.0D);
        }
        if (sizeBytes < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", sizeBytes / (1024.0D * 1024.0D));
        }
        return String.format(java.util.Locale.ROOT, "%.1f GB", sizeBytes / (1024.0D * 1024.0D * 1024.0D));
    }

    private String safe(String value, int maxLength) {
        String normalized = value == null ? "" : value.replace('`', '\'');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
