package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.HoneypotService;
import com.norule.musicbot.config.GuildSettingsService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HoneypotListener extends ListenerAdapter {
    public static final String CHANNEL_NAME = "\u8acb\u52ff\u767c\u9001\u8a0a\u606f";
    public static final String CHANNEL_TOPIC = "\u5bc6\u7f50\u983b\u9053\uff1a\u8acb\u52ff\u5728\u6b64\u767c\u9001\u4efb\u4f55\u8a0a\u606f\u3002\u4efb\u4f55\u8a0a\u606f\u90fd\u6703\u88ab\u7acb\u5373\u522a\u9664\uff0c\u767c\u9001\u8005\u6703\u88ab\u8e22\u51fa\u4f3a\u670d\u5668\u3002";
    public static final String WARNING_TITLE = "\u8acb\u52ff\u767c\u9001\u8a0a\u606f";
    public static final String WARNING_DESCRIPTION = "\u9019\u662f\u5b89\u5168\u5bc6\u7f50\u983b\u9053\u3002\u70ba\u9632\u6b62\u88ab\u76dc\u7528\u6216\u81ea\u52d5\u5316\u5e33\u865f\u767c\u9001\u5ee3\u544a\uff0c\u4efb\u4f55\u5728\u6b64\u983b\u9053\u767c\u9001\u7684\u8a0a\u606f\u90fd\u6703\u7acb\u5373\u522a\u9664\uff0c\u767c\u9001\u8005\u6703\u88ab\u8e22\u51fa\u4f3a\u670d\u5668\u3002";

    private final HoneypotService honeypotService;
    private final GuildSettingsService settingsService;
    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HoneypotMessageCleanup");
        thread.setDaemon(true);
        return thread;
    });

    public HoneypotListener(HoneypotService honeypotService,
                            GuildSettingsService settingsService) {
        this.honeypotService = honeypotService;
        this.settingsService = settingsService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        Guild guild = event.getGuild();
        if (!honeypotService.isHoneypotChannel(guild.getIdLong(), event.getChannel().getIdLong())) {
            return;
        }
        if (event.getAuthor().equals(event.getJDA().getSelfUser())) {
            return;
        }

        event.getMessage().delete().reason("Honeypot channel message").queue(ignored -> {
        }, error -> {
        });

        Member member = event.getMember();
        if (member == null) {
            return;
        }
        cleanupExecutor.execute(() -> {
            int deletedMessages = deleteRecentMessages(guild, member.getIdLong(), Duration.ofHours(24));
            kickAndNotify(guild, member, deletedMessages);
        });
    }

    private void kickAndNotify(Guild guild, Member member, int deletedMessages) {
        Member self = guild.getSelfMember();
        if (member.isOwner() || !self.hasPermission(Permission.KICK_MEMBERS) || !self.canInteract(member)) {
            sendModerationNotice(guild, member, false, deletedMessages);
            return;
        }
        guild.kick(member).reason("Honeypot channel message").queue(
                success -> sendModerationNotice(guild, member, true, deletedMessages),
                error -> sendModerationNotice(guild, member, false, deletedMessages)
        );
    }

    private int deleteRecentMessages(Guild guild, long userId, Duration lookback) {
        Instant cutoff = Instant.now().minus(lookback);
        int deleted = 0;
        for (TextChannel channel : guild.getTextChannels()) {
            if (!canCleanChannel(guild, channel)) {
                continue;
            }
            try {
                deleted += deleteRecentMessagesInChannel(channel, userId, cutoff);
            } catch (Exception ignored) {
            }
        }
        return deleted;
    }

    private int deleteRecentMessagesInChannel(TextChannel channel, long userId, Instant cutoff) {
        int deleted = 0;
        MessageHistory history = channel.getHistory();
        List<Message> page = history.retrievePast(100).complete();
        while (!page.isEmpty()) {
            List<Message> matched = new ArrayList<>();
            boolean reachedCutoff = false;
            for (Message message : page) {
                Instant createdAt = message.getTimeCreated().toInstant();
                if (createdAt.isBefore(cutoff)) {
                    reachedCutoff = true;
                    continue;
                }
                if (message.getAuthor().getIdLong() == userId) {
                    matched.add(message);
                }
            }
            deleted += deleteBatch(channel, matched);
            if (reachedCutoff) {
                break;
            }
            String before = page.get(page.size() - 1).getId();
            page = MessageHistory.getHistoryBefore(channel, before).limit(100).complete().getRetrievedHistory();
        }
        return deleted;
    }

    private int deleteBatch(TextChannel channel, List<Message> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        List<Message> buffer = new ArrayList<>();
        for (Message message : messages) {
            buffer.add(message);
            if (buffer.size() == 100) {
                channel.deleteMessages(buffer).complete();
                deleted += buffer.size();
                buffer = new ArrayList<>();
            }
        }
        if (!buffer.isEmpty()) {
            if (buffer.size() == 1) {
                channel.deleteMessageById(buffer.get(0).getId()).complete();
            } else {
                channel.deleteMessages(buffer).complete();
            }
            deleted += buffer.size();
        }
        return deleted;
    }

    private boolean canCleanChannel(Guild guild, TextChannel channel) {
        Member self = guild.getSelfMember();
        return self.hasAccess(channel)
                && self.hasPermission(channel, Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE);
    }

    private void sendModerationNotice(Guild guild, Member member, boolean kicked, int deletedMessages) {
        TextChannel target = resolveNotifyChannel(guild);
        if (target == null || !target.canTalk()) {
            return;
        }
        String title = kicked ? "\u5bc6\u7f50\u983b\u9053\u89f8\u767c" : "\u5bc6\u7f50\u983b\u9053\u89f8\u767c\uff08\u672a\u80fd\u8e22\u51fa\uff09";
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(kicked ? new Color(231, 76, 60) : new Color(241, 196, 15))
                .setTitle(title)
                .addField("\u4f7f\u7528\u8005", member.getAsMention() + " (`" + member.getUser().getAsTag() + "`)", false)
                .addField("\u52d5\u4f5c", kicked ? "\u8a0a\u606f\u5df2\u522a\u9664\uff0c\u4f7f\u7528\u8005\u5df2\u8e22\u51fa" : "\u8a0a\u606f\u5df2\u522a\u9664\uff0c\u4f46\u6b0a\u9650\u6216\u8eab\u5206\u7d44\u5c64\u7d1a\u4e0d\u8db3\u7121\u6cd5\u8e22\u51fa", false)
                .addField("\u6e05\u7406\u8a0a\u606f", "\u5df2\u522a\u9664\u8a72\u4f7f\u7528\u8005 24 \u5c0f\u6642\u5167\u7684 `" + deletedMessages + "` \u5247\u8a0a\u606f", false)
                .setTimestamp(Instant.now());
        target.sendMessageEmbeds(eb.build()).queue(ignored -> {
        }, error -> {
        });
    }

    private TextChannel resolveNotifyChannel(Guild guild) {
        var logs = settingsService.getMessageLogs(guild.getIdLong());
        Long targetId = logs.getModerationLogChannelId() != null ? logs.getModerationLogChannelId() : logs.getChannelId();
        return targetId == null ? null : guild.getTextChannelById(targetId);
    }

    public static EmbedBuilder warningEmbed() {
        return new EmbedBuilder()
                .setColor(new Color(231, 76, 60))
                .setTitle(WARNING_TITLE)
                .setDescription(WARNING_DESCRIPTION)
                .setTimestamp(Instant.now());
    }
}
