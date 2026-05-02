package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.*;
import com.norule.musicbot.i18n.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.awt.Color;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageLogService {
    private static final long CACHE_RETENTION_MILLIS = Duration.ofDays(7).toMillis();
    private static final int EMBED_FIELD_LIMIT = 1024;
    private static final String EMPTY_TEXT = "(empty)";

    private static class CachedMessage {
        final long channelId;
        final String authorTag;
        final String authorId;
        final boolean authorIsBot;
        final List<Long> authorRoleIds;
        final String content;
        final String attachments;
        final long cachedAtMillis;

        CachedMessage(long channelId, String authorTag, String authorId, boolean authorIsBot, List<Long> authorRoleIds, String content, String attachments, long cachedAtMillis) {
            this.channelId = channelId;
            this.authorTag = authorTag;
            this.authorId = authorId;
            this.authorIsBot = authorIsBot;
            this.authorRoleIds = authorRoleIds == null ? List.of() : authorRoleIds;
            this.content = content;
            this.attachments = attachments;
            this.cachedAtMillis = cachedAtMillis;
        }
    }

    private final GuildSettingsService settingsService;
    private final I18nService i18n;
    private final Map<Long, CachedMessage> cache = new ConcurrentHashMap<>();
    private final Path cacheFile;
    private final Yaml yaml;

    public MessageLogService(GuildSettingsService settingsService, I18nService i18n, Path cacheDir) {
        this.settingsService = settingsService;
        this.i18n = i18n;
        this.cacheFile = cacheDir.resolve("message-log-cache.yml");
        this.yaml = new Yaml(yamlOptions());
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception ignored) {
        }
        loadCache();
        pruneAndSave(System.currentTimeMillis());
    }
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }
        Message msg = event.getMessage();
        BotConfig.MessageLogs cfg = settingsService.getMessageLogs(event.getGuild().getIdLong());
        List<Long> authorRoleIds = resolveRoleIds(event.getMember());
        if (shouldIgnoreMessage(cfg, event.getChannel().getIdLong(), event.getAuthor().getId(), authorRoleIds, msg.getContentRaw())) {
            cache.remove(msg.getIdLong());
            return;
        }
        long now = System.currentTimeMillis();
        pruneExpired(now);
        cache.put(msg.getIdLong(), new CachedMessage(
                event.getChannel().getIdLong(),
                event.getAuthor().getAsTag(),
                event.getAuthor().getId(),
                event.getAuthor().isBot(),
                authorRoleIds,
                msg.getContentRaw(),
                formatAttachments(msg),
                now
        ));
        saveCache();
    }
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        BotConfig.MessageLogs cfg = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!cfg.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);
        CachedMessage old = cache.get(event.getMessageIdLong());
        String afterRawContent = event.getMessage().getContentRaw();
        String afterRawAttachments = formatAttachments(event.getMessage());
        List<Long> authorRoleIds = resolveRoleIds(event.getMember());
        if (shouldIgnoreMessage(cfg, event.getChannel().getIdLong(), event.getAuthor().getId(), authorRoleIds, afterRawContent)) {
            cache.remove(event.getMessageIdLong());
            saveCache();
            return;
        }

        cache.put(event.getMessageIdLong(), new CachedMessage(
                event.getChannel().getIdLong(),
                event.getAuthor().getAsTag(),
                event.getAuthor().getId(),
                event.getAuthor().isBot(),
                authorRoleIds,
                afterRawContent,
                afterRawAttachments,
                now
        ));
        saveCache();

        if (old == null) {
            // No baseline snapshot (e.g. bot restarted); skip to avoid false-positive edit logs.
            return;
        }

        if (sameMeaningfulContent(old.content, afterRawContent) && sameMeaningfulContent(old.attachments, afterRawAttachments)) {
            // Ignore metadata-only updates such as link unfurl/embed refresh.
            return;
        }

        String before = formatRawForEmbed(old.content);
        String beforeAttachments = trimForField(old.attachments);
        String after = formatRawForEmbed(afterRawContent);
        String afterAttachments = trimForField(afterRawAttachments);

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
        saveCache();
        if (old == null) {
            return;
        }
        if (old.authorIsBot || event.getJDA().getSelfUser().getId().equals(old.authorId)) {
            return;
        }
        if (shouldIgnoreMessage(cfg, old.channelId, old.authorId, old.authorRoleIds, old.content)) {
            return;
        }
        String author = "<@" + old.authorId + "> (`" + old.authorTag + "`, `" + old.authorId + "`)";
        String content = formatRawForEmbed(old.content);
        String attachments = trimForField(old.attachments);
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

    private boolean shouldIgnoreMessage(BotConfig.MessageLogs cfg, long channelId, String authorId, List<Long> authorRoleIds, String content) {
        if (cfg.getIgnoredChannelIds().contains(channelId)) {
            return true;
        }
        Long authorIdLong = parseLong(authorId);
        if (authorIdLong != null && cfg.getIgnoredMemberIds().contains(authorIdLong)) {
            return true;
        }
        if (authorRoleIds != null && !authorRoleIds.isEmpty()) {
            for (Long roleId : authorRoleIds) {
                if (roleId != null && cfg.getIgnoredRoleIds().contains(roleId)) {
                    return true;
                }
            }
        }
        String raw = content == null ? "" : content;
        for (String prefix : cfg.getIgnoredPrefixes()) {
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

    private Long resolveMessageLogChannelId(Guild guild, BotConfig.MessageLogs cfg) {
        Long channelId = cfg.getMessageLogChannelId();
        if (channelId != null) {
            return channelId;
        }
        return cfg.getChannelId();
    }

    private void pruneExpired(long nowMillis) {
        cache.entrySet().removeIf(entry -> nowMillis - entry.getValue().cachedAtMillis > CACHE_RETENTION_MILLIS);
    }

    private synchronized void pruneAndSave(long nowMillis) {
        pruneExpired(nowMillis);
        saveCache();
    }

    private synchronized void loadCache() {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try (InputStream in = Files.newInputStream(cacheFile)) {
            Object rootObj = yaml.load(in);
            Map<String, Object> root = asMap(rootObj);
            Object entriesObj = root.get("entries");
            if (!(entriesObj instanceof Iterable<?> iterable)) {
                return;
            }
            for (Object rowObj : iterable) {
                Map<String, Object> row = asMap(rowObj);
                Long messageId = readLong(row.get("messageId"));
                Long channelId = readLong(row.get("channelId"));
                String authorTag = readString(row.get("authorTag"));
                String authorId = readString(row.get("authorId"));
                boolean authorIsBot = readBoolean(row.get("authorIsBot"));
                List<Long> authorRoleIds = readLongList(row.get("authorRoleIds"));
                String content = readString(row.get("content"));
                String attachments = readString(row.get("attachments"));
                Long cachedAt = readLong(row.get("cachedAtMillis"));
                if (messageId == null || channelId == null || authorId.isBlank() || cachedAt == null) {
                    continue;
                }
                cache.put(messageId, new CachedMessage(channelId, authorTag, authorId, authorIsBot, authorRoleIds, content, attachments, cachedAt));
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveCache() {
        try {
            Files.createDirectories(cacheFile.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<Long, CachedMessage> entry : cache.entrySet()) {
                CachedMessage value = entry.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("messageId", entry.getKey());
                row.put("channelId", value.channelId);
                row.put("authorTag", value.authorTag);
                row.put("authorId", value.authorId);
                row.put("authorIsBot", value.authorIsBot);
                row.put("authorRoleIds", value.authorRoleIds);
                row.put("content", value.content);
                row.put("attachments", value.attachments);
                row.put("cachedAtMillis", value.cachedAtMillis);
                entries.add(row);
            }
            root.put("entries", entries);
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                yaml.dump(root, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private DumperOptions yamlOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return options;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Long readLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean readBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<Long> readLongList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : iterable) {
            Long parsed = readLong(item);
            if (parsed != null && parsed > 0L) {
                result.add(parsed);
            }
        }
        return result.stream().distinct().toList();
    }
}







