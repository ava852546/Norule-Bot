package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.*;
import com.norule.musicbot.i18n.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class DuplicateMessageService {
    private static final long DUPLICATE_WINDOW_MILLIS = 120_000L;
    private static final int DUPLICATE_TRIGGER_COUNT = 3;
    private static final long CACHE_RETENTION_MILLIS = Duration.ofDays(7).toMillis();
    private static final long PRUNE_INTERVAL_MILLIS = Duration.ofMinutes(3).toMillis();

    private final GuildSettingsService settingsService;
    private final ModerationService moderationService;
    private final I18nService i18n;
    private final DuplicateMessageCacheRepository cacheRepository;
    private final Object cacheLock = new Object();
    private volatile long lastPruneAtMillis = 0L;

    public DuplicateMessageService(GuildSettingsService settingsService,
                                    ModerationService moderationService,
                                    I18nService i18n,
                                    DuplicateMessageCacheRepository cacheRepository) {
        this.settingsService = settingsService;
        this.moderationService = moderationService;
        this.i18n = i18n;
        this.cacheRepository = cacheRepository;
        pruneExpiredIfNeeded(System.currentTimeMillis(), true);
    }

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
        pruneExpiredIfNeeded(now, false);
        int count = upsertAndCount(
                guildId,
                event.getChannel().getIdLong(),
                event.getAuthor().getIdLong(),
                sha256(normalized),
                now
        );
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
        var logs = settingsService.getMessageLogs(guild.getIdLong());
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

    private int upsertAndCount(long guildId, long channelId, long userId, String contentHash, long nowMillis) {
        synchronized (cacheLock) {
            DuplicateMessageCacheEntry previous;
            try {
                previous = cacheRepository.find(guildId, channelId, userId, contentHash);
            } catch (Exception ignored) {
                return 0;
            }
            int count = 1;
            if (previous != null && nowMillis - previous.getTimestampMillis() <= DUPLICATE_WINDOW_MILLIS) {
                count = previous.getCount() + 1;
            }
            try {
                cacheRepository.upsert(new DuplicateMessageCacheEntry(
                        guildId,
                        channelId,
                        userId,
                        contentHash,
                        count,
                        nowMillis
                ));
            } catch (Exception ignored) {
                return 0;
            }
            return count;
        }
    }

    private void pruneExpiredIfNeeded(long nowMillis, boolean force) {
        if (!force && nowMillis - lastPruneAtMillis < PRUNE_INTERVAL_MILLIS) {
            return;
        }
        synchronized (cacheLock) {
            if (!force && nowMillis - lastPruneAtMillis < PRUNE_INTERVAL_MILLIS) {
                return;
            }
            try {
                cacheRepository.pruneExpired(nowMillis - CACHE_RETENTION_MILLIS);
                lastPruneAtMillis = nowMillis;
            } catch (Exception ignored) {
            }
        }
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







