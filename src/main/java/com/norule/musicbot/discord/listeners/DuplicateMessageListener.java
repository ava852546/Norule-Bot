package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.awt.Color;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class DuplicateMessageListener extends ListenerAdapter {
    private static final long DUPLICATE_WINDOW_MILLIS = 120_000L;
    private static final int DUPLICATE_TRIGGER_COUNT = 3;
    private static final long CACHE_RETENTION_MILLIS = Duration.ofDays(7).toMillis();

    private record DuplicateKey(long guildId, long channelId, long userId, String contentHash) {}
    private record DuplicateRecord(int count, long timestampMillis) {}

    private final GuildSettingsService settingsService;
    private final ModerationService moderationService;
    private final I18nService i18n;
    private final Map<DuplicateKey, DuplicateRecord> recent = new ConcurrentHashMap<>();
    private final Path cacheFile;
    private final Yaml yaml;

    public DuplicateMessageListener(GuildSettingsService settingsService,
                                    ModerationService moderationService,
                                    I18nService i18n,
                                    Path cacheDir) {
        this.settingsService = settingsService;
        this.moderationService = moderationService;
        this.i18n = i18n;
        this.cacheFile = cacheDir.resolve("duplicate-message-cache.yml");
        this.yaml = new Yaml(yamlOptions());
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception ignored) {
        }
        loadCache();
        pruneAndSave(System.currentTimeMillis());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        long guildId = event.getGuild().getIdLong();
        if (!moderationService.isDuplicateDetectionEnabled(guildId)) {
            return;
        }

        String normalized = normalize(event.getMessage());
        if (normalized.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);
        DuplicateKey key = new DuplicateKey(
                guildId,
                event.getChannel().getIdLong(),
                event.getAuthor().getIdLong(),
                sha256(normalized)
        );

        DuplicateRecord previous = recent.get(key);
        if (previous == null || now - previous.timestampMillis() > DUPLICATE_WINDOW_MILLIS) {
            recent.put(key, new DuplicateRecord(1, now));
            saveCache();
            return;
        }

        int count = previous.count() + 1;
        recent.put(key, new DuplicateRecord(count, now));
        saveCache();
        if (count < DUPLICATE_TRIGGER_COUNT) {
            return;
        }

        handleDuplicate(event, count);
    }

    private void handleDuplicate(MessageReceivedEvent event, int duplicateCount) {
        event.getMessage().delete().queue(ignored -> {
        }, error -> {
        });

        Member member = event.getMember();
        if (member == null) {
            return;
        }
        Guild guild = event.getGuild();
        long guildId = guild.getIdLong();
        String lang = settingsService.getLanguage(guildId);
        int warningCount = moderationService.addWarnings(guildId, member.getIdLong(), 1);

        boolean timeoutAllowed = canTimeout(guild, member);
        if (timeoutAllowed) {
            member.timeoutFor(Duration.ofMinutes(10))
                    .reason("Auto moderation: duplicate messages")
                    .queue(success -> {
                    }, error -> {
                    });
        }

        TextChannel target = resolveNotifyChannel(event);
        if (target == null) {
            return;
        }

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(231, 76, 60))
                .setTitle(i18n.t(lang, "moderation.duplicate.title"))
                .addField(i18n.t(lang, "moderation.duplicate.user"), member.getAsMention() + " (`" + member.getUser().getAsTag() + "`)", false)
                .addField(i18n.t(lang, "moderation.duplicate.channel"), "<#" + event.getChannel().getId() + ">", true)
                .addField(i18n.t(lang, "moderation.duplicate.warning_count"), String.valueOf(warningCount), true)
                .addField(i18n.t(lang, "moderation.duplicate.duplicate_count"), String.valueOf(duplicateCount), true)
                .addField(i18n.t(lang, "moderation.duplicate.action"),
                        timeoutAllowed ? i18n.t(lang, "moderation.duplicate.action_timeout")
                                : i18n.t(lang, "moderation.duplicate.action_warn_only"), false)
                .addField(i18n.t(lang, "moderation.duplicate.content"),
                        "```text\n" + safe(event.getMessage().getContentRaw()) + "\n```", false)
                .setTimestamp(Instant.now());
        target.sendMessageEmbeds(eb.build()).queue(ignored -> {
        }, error -> {
        });
    }

    private TextChannel resolveNotifyChannel(MessageReceivedEvent event) {
        Guild guild = event.getGuild();
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(guild.getIdLong());
        Long targetId = logs.getModerationLogChannelId() != null ? logs.getModerationLogChannelId() : logs.getChannelId();
        TextChannel target = targetId == null ? null : guild.getTextChannelById(targetId);
        if (target != null) {
            return target;
        }
        return event.getChannelType().isMessage() && event.getChannel() instanceof TextChannel text ? text : null;
    }

    private boolean canTimeout(Guild guild, Member target) {
        Member self = guild.getSelfMember();
        if (self == null || target.isOwner()) {
            return false;
        }
        if (!self.hasPermission(Permission.MODERATE_MEMBERS)) {
            return false;
        }
        return self.canInteract(target);
    }

    private String normalize(Message message) {
        String raw = message.getContentRaw();
        if (raw == null) {
            raw = "";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }

    private String safe(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        String cleaned = text.replace("```", "'''");
        return cleaned.length() <= 900 ? cleaned : cleaned.substring(0, 900) + "...";
    }

    private void pruneExpired(long nowMillis) {
        recent.entrySet().removeIf(entry -> nowMillis - entry.getValue().timestampMillis() > CACHE_RETENTION_MILLIS);
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
            for (Object row : iterable) {
                Map<String, Object> map = asMap(row);
                Long guildId = readLong(map.get("guildId"));
                Long channelId = readLong(map.get("channelId"));
                Long userId = readLong(map.get("userId"));
                String contentHash = readString(map.get("contentHash"));
                Integer count = readInt(map.get("count"));
                Long ts = readLong(map.get("timestampMillis"));
                if (guildId == null || channelId == null || userId == null
                        || contentHash.isBlank() || count == null || ts == null) {
                    continue;
                }
                recent.put(new DuplicateKey(guildId, channelId, userId, contentHash),
                        new DuplicateRecord(Math.max(1, count), ts));
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveCache() {
        try {
            Files.createDirectories(cacheFile.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<DuplicateKey, DuplicateRecord> entry : recent.entrySet()) {
                DuplicateKey key = entry.getKey();
                DuplicateRecord value = entry.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("guildId", key.guildId());
                row.put("channelId", key.channelId());
                row.put("userId", key.userId());
                row.put("contentHash", key.contentHash());
                row.put("count", value.count());
                row.put("timestampMillis", value.timestampMillis());
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
        String s = String.valueOf(value).trim();
        if (s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer readInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }
}


