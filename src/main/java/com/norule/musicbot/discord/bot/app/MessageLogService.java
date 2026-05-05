package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class MessageLogService {
    private static final long CACHE_RETENTION_MILLIS = Duration.ofDays(7).toMillis();
    private static final int EMBED_FIELD_LIMIT = 1024;
    private static final String EMPTY_TEXT = "(empty)";

    private final GuildSettingsService settingsService;
    private final I18nService i18n;
    private final MessageLogCacheRepository cacheRepository;

    public MessageLogService(GuildSettingsService settingsService, I18nService i18n, MessageLogCacheRepository cacheRepository) {
        this.settingsService = settingsService;
        this.i18n = i18n;
        this.cacheRepository = cacheRepository;
        pruneExpired(System.currentTimeMillis());
    }

    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }
        Message msg = event.getMessage();
        var cfg = settingsService.getMessageLogs(event.getGuild().getIdLong());
        List<Long> authorRoleIds = resolveRoleIds(event.getMember());
        long now = System.currentTimeMillis();
        pruneExpired(now);
        if (shouldIgnoreMessage(
                cfg.getIgnoredChannelIds(),
                cfg.getIgnoredMemberIds(),
                cfg.getIgnoredRoleIds(),
                cfg.getIgnoredPrefixes(),
                event.getChannel().getIdLong(),
                event.getAuthor().getId(),
                authorRoleIds,
                msg.getContentRaw())) {
            removeCacheEntry(msg.getIdLong());
            return;
        }
        upsertCacheEntry(new MessageLogCacheEntry(
                msg.getIdLong(),
                event.getChannel().getIdLong(),
                event.getAuthor().getAsTag(),
                event.getAuthor().getId(),
                event.getAuthor().isBot(),
                authorRoleIds,
                msg.getContentRaw(),
                formatAttachments(msg),
                now
        ));
    }

    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        var cfg = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!cfg.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);
        MessageLogCacheEntry old = findCacheEntry(event.getMessageIdLong());
        String afterRawContent = event.getMessage().getContentRaw();
        String afterRawAttachments = formatAttachments(event.getMessage());
        List<Long> authorRoleIds = resolveRoleIds(event.getMember());
        if (shouldIgnoreMessage(
                cfg.getIgnoredChannelIds(),
                cfg.getIgnoredMemberIds(),
                cfg.getIgnoredRoleIds(),
                cfg.getIgnoredPrefixes(),
                event.getChannel().getIdLong(),
                event.getAuthor().getId(),
                authorRoleIds,
                afterRawContent)) {
            removeCacheEntry(event.getMessageIdLong());
            return;
        }

        upsertCacheEntry(new MessageLogCacheEntry(
                event.getMessageIdLong(),
                event.getChannel().getIdLong(),
                event.getAuthor().getAsTag(),
                event.getAuthor().getId(),
                event.getAuthor().isBot(),
                authorRoleIds,
                afterRawContent,
                afterRawAttachments,
                now
        ));

        if (old == null) {
            return;
        }

        if (sameMeaningfulContent(old.getContent(), afterRawContent)
                && sameMeaningfulContent(old.getAttachments(), afterRawAttachments)) {
            return;
        }

        String before = formatRawForEmbed(old.getContent());
        String beforeAttachments = trimForField(old.getAttachments());
        String after = formatRawForEmbed(afterRawContent);
        String afterAttachments = trimForField(afterRawAttachments);

        sendLog(event.getGuild(), resolveMessageLogChannelId(cfg.getMessageLogChannelId(), cfg.getChannelId()), new EmbedBuilder()
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

    public void onMessageDelete(MessageDeleteEvent event) {
        if (!event.isFromGuild()) {
            return;
        }

        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        var cfg = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!cfg.isEnabled()) {
            return;
        }

        MessageLogCacheEntry old = removeCacheEntry(event.getMessageIdLong());
        if (old == null) {
            return;
        }
        if (old.isAuthorIsBot() || event.getJDA().getSelfUser().getId().equals(old.getAuthorId())) {
            return;
        }
        if (shouldIgnoreMessage(
                cfg.getIgnoredChannelIds(),
                cfg.getIgnoredMemberIds(),
                cfg.getIgnoredRoleIds(),
                cfg.getIgnoredPrefixes(),
                old.getChannelId(),
                old.getAuthorId(),
                old.getAuthorRoleIds(),
                old.getContent())) {
            return;
        }
        String author = "<@" + old.getAuthorId() + "> (`" + old.getAuthorTag() + "`, `" + old.getAuthorId() + "`)";
        String content = formatRawForEmbed(old.getContent());
        String attachments = trimForField(old.getAttachments());
        long channelId = old.getChannelId();

        sendLog(event.getGuild(), resolveMessageLogChannelId(cfg.getMessageLogChannelId(), cfg.getChannelId()), new EmbedBuilder()
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

    private void pruneExpired(long nowMillis) {
        long cutoff = nowMillis - CACHE_RETENTION_MILLIS;
        try {
            cacheRepository.pruneExpired(cutoff);
        } catch (Exception ignored) {
        }
    }

    private void upsertCacheEntry(MessageLogCacheEntry entry) {
        try {
            cacheRepository.upsert(entry);
        } catch (Exception ignored) {
        }
    }

    private MessageLogCacheEntry findCacheEntry(long messageId) {
        try {
            return cacheRepository.find(messageId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private MessageLogCacheEntry removeCacheEntry(long messageId) {
        try {
            return cacheRepository.remove(messageId);
        } catch (Exception ignored) {
            return null;
        }
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
        if (safe.isBlank()) {
            safe = EMPTY_TEXT;
        }
        String prefix = "```text\n";
        String suffix = "\n```";
        int maxRawLength = EMBED_FIELD_LIMIT - prefix.length() - suffix.length();
        if (safe.length() > maxRawLength) {
            safe = safe.substring(0, Math.max(0, maxRawLength - 3)) + "...";
        }
        return prefix + safe + suffix;
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

    private boolean shouldIgnoreMessage(List<Long> ignoredChannelIds,
                                        List<Long> ignoredMemberIds,
                                        List<Long> ignoredRoleIds,
                                        List<String> ignoredPrefixes,
                                        long channelId,
                                        String authorId,
                                        List<Long> authorRoleIds,
                                        String content) {
        if (ignoredChannelIds.contains(channelId)) {
            return true;
        }
        Long authorIdLong = parseLong(authorId);
        if (authorIdLong != null && ignoredMemberIds.contains(authorIdLong)) {
            return true;
        }
        if (authorRoleIds != null && !authorRoleIds.isEmpty()) {
            for (Long roleId : authorRoleIds) {
                if (roleId != null && ignoredRoleIds.contains(roleId)) {
                    return true;
                }
            }
        }
        String raw = content == null ? "" : content;
        for (String prefix : ignoredPrefixes) {
            if (prefix != null && !prefix.isBlank() && raw.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<Long> resolveRoleIds(Member member) {
        if (member == null || member.getRoles().isEmpty()) {
            return List.of();
        }
        return member.getRoles().stream()
                .map(role -> role == null ? null : role.getIdLong())
                .filter(roleId -> roleId != null && roleId > 0L)
                .distinct()
                .toList();
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String trimForField(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY_TEXT;
        }
        if (text.length() > EMBED_FIELD_LIMIT) {
            return text.substring(0, EMBED_FIELD_LIMIT - 3) + "...";
        }
        return text;
    }

    private boolean sameMeaningfulContent(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        return a.equals(b);
    }

    private Long resolveMessageLogChannelId(Long messageLogChannelId, Long fallbackChannelId) {
        Long channelId = messageLogChannelId;
        if (channelId != null) {
            return channelId;
        }
        return fallbackChannelId;
    }
}
