package com.norule.musicbot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageLogListener extends ListenerAdapter {
    private static class CachedMessage {
        final long channelId;
        final String authorTag;
        final String authorId;
        final boolean authorIsBot;
        final String content;
        final String attachments;

        CachedMessage(long channelId, String authorTag, String authorId, boolean authorIsBot, String content, String attachments) {
            this.channelId = channelId;
            this.authorTag = authorTag;
            this.authorId = authorId;
            this.authorIsBot = authorIsBot;
            this.content = content;
            this.attachments = attachments;
        }
    }

    private final GuildSettingsService settingsService;
    private final I18nService i18n;
    private final Map<Long, CachedMessage> cache = new ConcurrentHashMap<>();

    public MessageLogListener(GuildSettingsService settingsService, I18nService i18n) {
        this.settingsService = settingsService;
        this.i18n = i18n;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }
        Message msg = event.getMessage();
        cache.put(msg.getIdLong(), new CachedMessage(
                event.getChannel().getIdLong(),
                event.getAuthor().getAsTag(),
                event.getAuthor().getId(),
                event.getAuthor().isBot(),
                msg.getContentRaw(),
                formatAttachments(msg)
        ));
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        BotConfig.MessageLogs cfg = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!cfg.isEnabled()) {
            return;
        }

        CachedMessage old = cache.get(event.getMessageIdLong());
        String before = old == null ? "(no cache)" : formatRawForEmbed(trim(old.content));
        String beforeAttachments = old == null ? "(no cache)" : trim(old.attachments);
        String after = formatRawForEmbed(trim(event.getMessage().getContentRaw()));
        String afterAttachments = trim(formatAttachments(event.getMessage()));

        cache.put(event.getMessageIdLong(), new CachedMessage(
                event.getChannel().getIdLong(),
                event.getAuthor().getAsTag(),
                event.getAuthor().getId(),
                event.getAuthor().isBot(),
                event.getMessage().getContentRaw(),
                formatAttachments(event.getMessage())
        ));

        sendLog(event.getGuild(), resolveMessageLogChannelId(event.getGuild(), cfg), new EmbedBuilder()
                .setColor(new Color(241, 196, 15))
                .setTitle(i18n.t(lang, "message_logs.edit.title"))
                .addField(i18n.t(lang, "message_logs.edit.user"), event.getAuthor().getAsMention() + " (`" + event.getAuthor().getAsTag() + "`)", false)
                .addField(i18n.t(lang, "message_logs.edit.channel"), "<#" + event.getChannel().getId() + ">", true)
                .addField(i18n.t(lang, "message_logs.edit.message_id"), event.getMessageId(), true)
                .addField(i18n.t(lang, "message_logs.edit.before"), before, false)
                .addField(i18n.t(lang, "message_logs.edit.before_attachments"), beforeAttachments, false)
                .addField(i18n.t(lang, "message_logs.edit.after"), after, false)
                .addField(i18n.t(lang, "message_logs.edit.after_attachments"), afterAttachments, false)
                .setTimestamp(Instant.now())
        );
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (!event.isFromGuild()) {
            return;
        }

        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        BotConfig.MessageLogs cfg = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!cfg.isEnabled()) {
            return;
        }

        CachedMessage old = cache.remove(event.getMessageIdLong());
        if (old == null) {
            return;
        }
        if (old.authorIsBot || event.getJDA().getSelfUser().getId().equals(old.authorId)) {
            return;
        }
        String author = "<@" + old.authorId + "> (`" + old.authorTag + "`, `" + old.authorId + "`)";
        String content = formatRawForEmbed(trim(old.content));
        String attachments = trim(old.attachments);
        long channelId = old.channelId;

        sendLog(event.getGuild(), resolveMessageLogChannelId(event.getGuild(), cfg), new EmbedBuilder()
                .setColor(new Color(231, 76, 60))
                .setTitle(i18n.t(lang, "message_logs.delete.title"))
                .addField(i18n.t(lang, "message_logs.delete.author"), author, false)
                .addField(i18n.t(lang, "message_logs.delete.channel"), "<#" + channelId + ">", true)
                .addField(i18n.t(lang, "message_logs.delete.message_id"), event.getMessageId(), true)
                .addField(i18n.t(lang, "message_logs.delete.content"), content, false)
                .addField(i18n.t(lang, "message_logs.delete.attachments"), attachments, false)
                .setTimestamp(Instant.now())
        );
    }

    private String formatAttachments(Message message) {
        if (message.getAttachments().isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        int max = Math.min(5, message.getAttachments().size());
        for (int i = 0; i < max; i++) {
            Message.Attachment attachment = message.getAttachments().get(i);
            String type = attachment.getContentType();
            if (type == null || type.isBlank()) {
                type = fileExtension(attachment.getFileName());
            }
            if (type == null || type.isBlank()) {
                type = "unknown";
            }
            long kb = Math.max(1L, attachment.getSize() / 1024L);
            sb.append(i + 1)
                    .append(". ")
                    .append(attachment.getFileName())
                    .append(" (")
                    .append(type)
                    .append(", ")
                    .append(kb)
                    .append(" KB)")
                    .append('\n')
                    .append("   ")
                    .append(attachment.getUrl())
                    .append('\n');
        }
        if (message.getAttachments().size() > max) {
            sb.append("...");
        }
        return sb.toString().trim();
    }

    private String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase();
    }

    private String formatRawForEmbed(String text) {
        String safe = text == null ? "" : text.replace("```", "'''");
        return "```text\n" + safe + "\n```";
    }

    private void sendLog(Guild guild, Long channelId, EmbedBuilder eb) {
        if (channelId == null) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel != null) {
            channel.sendMessageEmbeds(eb.build()).queue();
        }
    }

    private String trim(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        if (text.length() > 1024) {
            return text.substring(0, 1020) + "...";
        }
        return text;
    }

    private Long resolveMessageLogChannelId(Guild guild, BotConfig.MessageLogs cfg) {
        Long channelId = cfg.getMessageLogChannelId();
        if (channelId != null) {
            return channelId;
        }
        return cfg.getChannelId();
    }
}
