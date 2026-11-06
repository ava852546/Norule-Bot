package com.norule.musicbot.discord.bot.service.voice;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.MusicPanelMode;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.norule.musicbot.domain.music.MusicStateChange;
import com.norule.musicbot.domain.monitoring.VoiceAutoLeaveEvent;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.LongFunction;
import java.util.function.Consumer;

public class VoiceAutoLeaveService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceAutoLeaveService.class);
    private static final long IDLE_LEAVE_NOTICE_DELETE_DELAY_SECONDS = 30L;
    private static final long EMPTY_CHANNEL_SECONDS = 10;
    private static final long IDLE_PLAYBACK_SECONDS = 600;

    private enum LeaveReason { EMPTY_CHANNEL, IDLE_PLAYBACK }

    private static final class PendingLeave {
        private ScheduledFuture<?> future;
    }

    // All timer state and disconnect operations are confined to scheduler.
    private static final class ChannelState {
        private final long channelId;
        private final String channelName;
        private final Map<LeaveReason, PendingLeave> timers = new EnumMap<>(LeaveReason.class);
        private boolean disconnecting;

        private ChannelState(long channelId, String channelName) {
            this.channelId = channelId;
            this.channelName = channelName;
        }
    }

    private final Consumer<VoiceAutoLeaveEvent> events;
    private final LongFunction<MusicConfig> musicConfigs;
    private final LongFunction<String> languages;
    private final MusicPlayerService playerService;
    private final Supplier<I18nService> i18n;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, ChannelState> states = new HashMap<>();
    private final Runnable unsubscribePlayback;
    private JDA jda;
    private boolean monitorStarted;

    public VoiceAutoLeaveService(LongFunction<MusicConfig> musicConfigs, LongFunction<String> languages,
                                 MusicPlayerService playerService,
                                 Supplier<I18nService> i18n) {
        this(musicConfigs, languages, playerService, i18n, Executors.newSingleThreadScheduledExecutor());
    }

    public VoiceAutoLeaveService(LongFunction<MusicConfig> musicConfigs, LongFunction<String> languages,
            MusicPlayerService playerService, Supplier<I18nService> i18n,
            Consumer<VoiceAutoLeaveEvent> events) {
        this(musicConfigs, languages, playerService, i18n, Executors.newSingleThreadScheduledExecutor(), events);
    }

    VoiceAutoLeaveService(LongFunction<MusicConfig> musicConfigs, LongFunction<String> languages,
                          MusicPlayerService playerService,
                          Supplier<I18nService> i18n, ScheduledExecutorService scheduler) {
        this(musicConfigs, languages, playerService, i18n, scheduler, ignored -> {});
    }

    VoiceAutoLeaveService(LongFunction<MusicConfig> musicConfigs, LongFunction<String> languages,
                          MusicPlayerService playerService, Supplier<I18nService> i18n,
                          ScheduledExecutorService scheduler, Consumer<VoiceAutoLeaveEvent> events) {
        this.musicConfigs = musicConfigs;
        this.languages = languages;
        this.playerService = playerService;
        this.i18n = i18n;
        this.scheduler = scheduler;
        this.events = events;
        unsubscribePlayback = playerService.addStateChangeListener(this::onPlaybackStateChanged);
    }

    public void close() {
        unsubscribePlayback.run();
        submit(() -> {
            for (long id : List.copyOf(states.keySet())) clear(id, "SHUTDOWN");
            scheduler.shutdownNow();
        });
    }

    private void submit(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException error) {
            if (!scheduler.isShutdown()) throw error;
        }
    }

    public void onConnected(JDA current) {
        submit(() -> {
            jda = current;
            if (!monitorStarted) {
                monitorStarted = true;
                scheduler.scheduleAtFixedRate(this::evaluateAllGuilds, 20, 20, TimeUnit.SECONDS);
            }
            evaluateAllGuilds();
        });
    }

    public void onVoiceUpdate(Guild guild, boolean selfMoved) {
        AudioChannel channel = currentBotChannel(guild);
        boolean hasHumans = channel != null && humanCount(channel) > 0;
        submit(() -> {
            long id = guild.getIdLong();
            if (selfMoved) clear(id, "CHANNEL_CHANGED");
            // Preserve a human return even if they leave again before the queued check runs.
            ChannelState state = states.get(id);
            if (hasHumans && state != null) cancel(id, state, LeaveReason.EMPTY_CHANNEL, "USER_JOINED");
            evaluateSafely(guild);
        });
    }

    private void onPlaybackStateChanged(long guildId, MusicStateChange change) {
        submit(() -> {
            if (jda == null) return;
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) return;
            // Preserve resume/start transitions even if another pause follows before this task runs.
            if (change == MusicStateChange.RESUME || change == MusicStateChange.TRACK_START) {
                ChannelState state = states.get(guildId);
                if (state != null) cancel(guildId, state, LeaveReason.IDLE_PLAYBACK, "PLAYBACK_ACTIVE");
            }
            evaluateSafely(guild);
        });
    }

    private void evaluateAllGuilds() {
        if (jda == null) return;
        states.keySet().removeIf(id -> {
            if (jda.getGuildById(id) != null) return false;
            states.get(id).timers.values().forEach(timer -> timer.future.cancel(false));
            return true;
        });
        for (Guild guild : jda.getGuilds()) evaluateSafely(guild);
    }

    private void evaluateSafely(Guild guild) {
        try {
            evaluateGuild(guild);
        } catch (RuntimeException error) {
            LOGGER.warn("[VoiceAutoLeave] event=CHECK_FAILED guildId={}", guild.getIdLong(), error);
        }
    }

    private void evaluateGuild(Guild guild) {
        long id = guild.getIdLong();
        AudioChannel channel = currentBotChannel(guild);
        if (channel == null || !musicConfigs.apply(id).isAutoLeaveEnabled()) {
            clear(id, "DISABLED_OR_DISCONNECTED");
            return;
        }
        ChannelState state = states.get(id);
        if (state != null && state.channelId != channel.getIdLong()) {
            clear(id, "CHANNEL_CHANGED");
            state = null;
        }
        if (state == null) {
            state = new ChannelState(channel.getIdLong(), channel.getName());
            states.put(id, state);
        }
        if (state.disconnecting) return;
        updateTimer(guild, state, LeaveReason.EMPTY_CHANNEL, humanCount(channel) == 0,
                EMPTY_CHANNEL_SECONDS, "USER_JOINED");
        updateTimer(guild, state, LeaveReason.IDLE_PLAYBACK, idlePlayback(guild),
                IDLE_PLAYBACK_SECONDS, "PLAYBACK_ACTIVE");
    }

    private void updateTimer(Guild guild, ChannelState state, LeaveReason reason, boolean eligible,
                             long seconds, String cancellation) {
        long id = guild.getIdLong();
        if (!eligible) {
            cancel(id, state, reason, cancellation);
        } else if (!state.timers.containsKey(reason)) {
            PendingLeave timer = new PendingLeave();
            state.timers.put(reason, timer);
            timer.future = scheduler.schedule(() -> executeScheduledLeave(id, state, reason, timer),
                    seconds, TimeUnit.SECONDS);
            emit("TIMER_STARTED", reason.name(), guild, id, state, null, null, seconds);
        }
    }

    private void executeScheduledLeave(long id, ChannelState state, LeaveReason reason, PendingLeave timer) {
        if (states.get(id) != state || state.disconnecting || state.timers.get(reason) != timer) return;
        Guild guild = jda == null ? null : jda.getGuildById(id);
        if (guild == null) {
            clear(id, "GUILD_UNAVAILABLE");
            return;
        }
        try {
            AudioChannel channel = currentBotChannel(guild);
            if (channel == null || !musicConfigs.apply(id).isAutoLeaveEnabled()) {
                clear(id, "DISABLED_OR_DISCONNECTED");
                return;
            }
            if (channel.getIdLong() != state.channelId) {
                clear(id, "CHANNEL_CHANGED");
                evaluateSafely(guild);
                return;
            }
            long humans = humanCount(channel);
            // Recheck only this timer's condition; playback must never postpone an empty-channel exit.
            if (reason == LeaveReason.EMPTY_CHANNEL ? humans > 0 : !idlePlayback(guild)) {
                cancel(id, state, reason, reason == LeaveReason.EMPTY_CHANNEL ? "USER_JOINED" : "PLAYBACK_ACTIVE");
                return;
            }
            state.disconnecting = true;
            state.timers.values().forEach(pending -> pending.future.cancel(false));
            state.timers.clear();
            playerService.stop(guild);
            playerService.leaveChannel(guild);
            emit("DISCONNECTED", reason.name(), guild, id, state, humans,
                    reason == LeaveReason.IDLE_PLAYBACK ? IDLE_PLAYBACK_SECONDS : 0L, null);
        } catch (RuntimeException error) {
            clear(id, "DISCONNECT_FAILED");
            LOGGER.warn("[VoiceAutoLeave] event=DISCONNECT_FAILED guildId={} reason={}", id, reason, error);
            emit("ERROR", "DISCONNECT_FAILED", guild, id, state, null, null, null);
            return;
        }
        if (reason == LeaveReason.IDLE_PLAYBACK) {
            try {
                sendIdleLeaveNotice(guild);
            } catch (RuntimeException error) {
                LOGGER.warn("[VoiceAutoLeave] event=NOTICE_FAILED guildId={}", id, error);
            }
        }
    }

    private AudioChannel currentBotChannel(Guild guild) {
        var voice = guild.getSelfMember().getVoiceState();
        return voice == null ? null : voice.getChannel();
    }

    private long humanCount(AudioChannel channel) {
        return channel.getMembers().stream().filter(member -> !member.getUser().isBot()).count();
    }

    private boolean idlePlayback(Guild guild) {
        if (playerService.isPlaybackTransitioning(guild)) return false;
        return playerService.isPaused(guild)
                || (playerService.getCurrentTitle(guild) == null && playerService.getQueueSnapshot(guild).isEmpty());
    }

    private void cancel(long id, ChannelState state, LeaveReason reason, String cause) {
        PendingLeave timer = state.timers.remove(reason);
        if (timer != null) {
            timer.future.cancel(false);
            emit("TIMER_CANCELLED", cause, jda == null ? null : jda.getGuildById(id),
                    id, state, null, null, null);
        }
    }

    private void clear(long id, String cause) {
        ChannelState state = states.remove(id);
        if (state != null) {
            for (LeaveReason reason : LeaveReason.values()) cancel(id, state, reason, cause);
        }
    }

    private void emit(String event, String reason, Guild guild, long guildId, ChannelState state,
                      Long humans, Long idle, Long delay) {
        try {
            var channel = guild == null ? null : currentBotChannel(guild);
            if (channel != null && channel.getIdLong() != state.channelId) channel = null;
            if (humans == null && channel != null) humans = humanCount(channel);
            events.accept(new VoiceAutoLeaveEvent(Instant.now(), event, reason, guildId,
                    guild == null ? null : guild.getName(), state.channelId, state.channelName, humans, idle, delay));
        } catch (RuntimeException failure) {
            LOGGER.debug("[VoiceAutoLeave] Operational event delivery failed");
        }
    }

    private boolean modernCleanupEnabled(Guild guild) {
        var music = musicConfigs.apply(guild.getIdLong());
        if (music.getPanelMode() != MusicPanelMode.MODERN) {
            return false;
        }
        Long channelId = music.getCommandChannelId();
        TextChannel channel = channelId == null ? null : guild.getTextChannelById(channelId);
        return channel != null && guild.getSelfMember().hasPermission(channel,
                net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_SEND,
                net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS);
    }

    private void sendIdleLeaveNotice(Guild guild) {
        long guildId = guild.getIdLong();
        Long channelId = musicConfigs.apply(guildId).getCommandChannelId();
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
        String lang = languages.apply(guildId);
        channel.sendMessage(i18n.get().t(lang, "music.auto_leave_idle_notice")).queue(
                message -> message.delete().setCheck(() -> modernCleanupEnabled(guild)).queueAfter(
                        IDLE_LEAVE_NOTICE_DELETE_DELAY_SECONDS,
                        TimeUnit.SECONDS,
                        ignored -> {
                            // Notification deleted as scheduled.
                        },
                        error -> LOGGER.debug(
                                "[NoRule] Failed to delete idle auto-leave notice: guildId={} channelId={} messageId={}",
                                guildId,
                                channel.getIdLong(),
                                message.getIdLong(),
                                error
                        )
                ),
                error -> LOGGER.debug(
                        "[NoRule] Failed to send idle auto-leave notice: guildId={} channelId={}",
                        guildId,
                        channel.getIdLong(),
                        error
                )
        );
    }
}
