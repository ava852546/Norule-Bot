package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceAutoLeaveListener extends ListenerAdapter {
    private enum LeaveReason {
        EMPTY_CHANNEL,
        IDLE_PLAYBACK
    }

    private static class PendingLeave {
        private final LeaveReason reason;
        private final ScheduledFuture<?> future;

        private PendingLeave(LeaveReason reason, ScheduledFuture<?> future) {
            this.reason = reason;
            this.future = future;
        }
    }

    private final GuildSettingsService settingsService;
    private final MusicPlayerService playerService;
    private final I18nService i18n;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, PendingLeave> pendingLeaves = new ConcurrentHashMap<>();
    private final AtomicBoolean monitorStarted = new AtomicBoolean(false);
    private volatile JDA jda;

    public VoiceAutoLeaveListener(GuildSettingsService settingsService, MusicPlayerService playerService, I18nService i18n) {
        this.settingsService = settingsService;
        this.playerService = playerService;
        this.i18n = i18n;
    }

    @Override
    public void onReady(ReadyEvent event) {
        this.jda = event.getJDA();
        startMonitorIfNeeded();
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        this.jda = event.getJDA();
        startMonitorIfNeeded();
        evaluateGuild(event.getGuild());
    }

    private void startMonitorIfNeeded() {
        if (!monitorStarted.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::evaluateAllGuilds, 20, 20, TimeUnit.SECONDS);
    }

    private void evaluateAllGuilds() {
        JDA current = this.jda;
        if (current == null) {
            return;
        }
        for (Guild guild : current.getGuilds()) {
            evaluateGuild(guild);
        }
    }

    private void evaluateGuild(Guild guild) {
        long guildId = guild.getIdLong();
        BotConfig.Music cfg = settingsService.getMusic(guildId);
        if (!cfg.isAutoLeaveEnabled()) {
            cancel(guildId);
            return;
        }

        AudioChannel botChannel = currentBotChannel(guild);
        if (botChannel == null) {
            cancel(guildId);
            return;
        }

        LeaveReason reason = detectLeaveReason(guild, botChannel);
        if (reason == null) {
            cancel(guildId);
            return;
        }

        PendingLeave existing = pendingLeaves.get(guildId);
        if (existing != null) {
            if (existing.reason == reason) {
                return;
            }
            existing.future.cancel(false);
            pendingLeaves.remove(guildId);
        }

        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeScheduledLeave(guildId, reason),
                Math.max(1, cfg.getAutoLeaveMinutes()),
                TimeUnit.MINUTES
        );
        pendingLeaves.put(guildId, new PendingLeave(reason, future));
    }

    private void executeScheduledLeave(long guildId, LeaveReason expectedReason) {
        JDA current = this.jda;
        if (current == null) {
            pendingLeaves.remove(guildId);
            return;
        }
        Guild guild = current.getGuildById(guildId);
        if (guild == null) {
            pendingLeaves.remove(guildId);
            return;
        }

        AudioChannel botChannel = currentBotChannel(guild);
        if (botChannel == null) {
            pendingLeaves.remove(guildId);
            return;
        }

        LeaveReason reasonNow = detectLeaveReason(guild, botChannel);
        if (reasonNow == expectedReason) {
            playerService.stop(guild);
            playerService.leaveChannel(guild);
            if (reasonNow == LeaveReason.IDLE_PLAYBACK) {
                sendIdleLeaveNotice(guild);
            }
        }
        pendingLeaves.remove(guildId);
    }

    private AudioChannel currentBotChannel(Guild guild) {
        if (!guild.getAudioManager().isConnected() || guild.getSelfMember().getVoiceState() == null) {
            return null;
        }
        return (AudioChannel) guild.getSelfMember().getVoiceState().getChannel();
    }

    private LeaveReason detectLeaveReason(Guild guild, AudioChannel botChannel) {
        boolean hasNonBot = botChannel.getMembers().stream().anyMatch(member -> !member.getUser().isBot());
        if (!hasNonBot) {
            return LeaveReason.EMPTY_CHANNEL;
        }

        boolean idlePlayback = playerService.getCurrentTitle(guild) == null && playerService.getQueueSnapshot(guild).isEmpty();
        if (idlePlayback) {
            return LeaveReason.IDLE_PLAYBACK;
        }
        return null;
    }

    private void cancel(long guildId) {
        PendingLeave pending = pendingLeaves.remove(guildId);
        if (pending != null) {
            pending.future.cancel(false);
        }
    }

    private void sendIdleLeaveNotice(Guild guild) {
        long guildId = guild.getIdLong();
        Long channelId = settingsService.getMusic(guildId).getCommandChannelId();
        if (channelId == null) {
            channelId = playerService.getLastCommandChannelId(guildId);
        }
        if (channelId == null) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null || !channel.canTalk()) {
            return;
        }
        String lang = settingsService.getLanguage(guildId);
        channel.sendMessage(i18n.t(lang, "music.auto_leave_idle_notice")).queue(success -> {
        }, error -> {
        });
    }
}


