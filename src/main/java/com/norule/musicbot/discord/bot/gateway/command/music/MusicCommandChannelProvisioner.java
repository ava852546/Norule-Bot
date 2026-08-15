package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class MusicCommandChannelProvisioner {
    public static final String DEFAULT_CHANNEL_NAME = "norule-music";
    public static final long STARTUP_INTERVAL_MS = 1_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicCommandChannelProvisioner.class);
    private static final long FAILURE_LOG_COOLDOWN_MS = Duration.ofMinutes(10).toMillis();

    private final ChannelState channelState;
    private final ScheduledExecutorService scheduler;
    private final long startupIntervalMs;
    private final Map<Long, CompletableFuture<TextChannel>> provisioningByGuild = new ConcurrentHashMap<>();
    private final Set<Long> queuedGuilds = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastFailureLogAtByGuild = new ConcurrentHashMap<>();

    public MusicCommandChannelProvisioner(MusicCommandService owner, ScheduledExecutorService scheduler) {
        this(new OwnerChannelState(owner), scheduler, STARTUP_INTERVAL_MS);
    }

    MusicCommandChannelProvisioner(ChannelState channelState,
                                   ScheduledExecutorService scheduler,
                                   long startupIntervalMs) {
        if (channelState == null) {
            throw new IllegalArgumentException("channelState cannot be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler cannot be null");
        }
        if (startupIntervalMs < 0L) {
            throw new IllegalArgumentException("startupIntervalMs cannot be negative");
        }
        this.channelState = channelState;
        this.scheduler = scheduler;
        this.startupIntervalMs = startupIntervalMs;
    }

    public void queueStartupProvisioning(List<Guild> guilds,
                                         BiConsumer<Guild, TextChannel> onProvisioned) {
        if (guilds == null || guilds.isEmpty()) {
            return;
        }
        long delayMs = 0L;
        for (Guild guild : guilds) {
            if (queueProvisioning(guild, delayMs, onProvisioned)) {
                delayMs += startupIntervalMs;
            }
        }
    }

    public boolean queueProvisioning(Guild guild, BiConsumer<Guild, TextChannel> onProvisioned) {
        return queueProvisioning(guild, 0L, onProvisioned);
    }

    public CompletableFuture<TextChannel> ensureCommandChannel(Guild guild) {
        if (guild == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("guild cannot be null"));
        }

        TextChannel configured = configuredChannel(guild);
        if (configured != null) {
            rememberChannel(guild, configured);
            return CompletableFuture.completedFuture(configured);
        }

        long guildId = guild.getIdLong();
        CompletableFuture<TextChannel> future = provisioningByGuild.computeIfAbsent(
                guildId,
                ignored -> startProvisioning(guild)
        );
        future.whenComplete((channel, failure) -> provisioningByGuild.remove(guildId, future));
        return future;
    }

    public boolean adoptCommandChannel(Guild guild, TextChannel channel) {
        if (guild == null || channel == null || !canUseForMusicPanel(guild, channel)) {
            return false;
        }
        rememberChannel(guild, channel);
        return true;
    }

    public void logProvisioningFailure(Guild guild, Throwable failure) {
        if (guild == null || failure == null) {
            return;
        }
        Throwable cause = rootCause(failure);
        if (cause instanceof MissingManageChannelPermissionException) {
            logSkipped(guild.getIdLong(), "Missing permission: Manage Channels");
            return;
        }
        long guildId = guild.getIdLong();
        long now = System.currentTimeMillis();
        Long previous = lastFailureLogAtByGuild.putIfAbsent(guildId, now);
        if (previous != null) {
            if (now - previous < FAILURE_LOG_COOLDOWN_MS) {
                return;
            }
            if (!lastFailureLogAtByGuild.replace(guildId, previous, now)) {
                return;
            }
        }
        LOGGER.warn(
                "[NoRule] Music command channel provisioning failed: guildId={} reason={}",
                guildId,
                safeErrorMessage(cause)
        );
    }

    private boolean queueProvisioning(Guild guild,
                                      long delayMs,
                                      BiConsumer<Guild, TextChannel> onProvisioned) {
        if (guild == null) {
            return false;
        }
        long guildId = guild.getIdLong();
        if (!hasManageChannelPermission(guild)) {
            logSkipped(guildId, "Missing permission: Manage Channels");
            return false;
        }
        if (!queuedGuilds.add(guildId)) {
            LOGGER.debug(
                    "[NoRule] Music command channel provisioning already queued: guildId={}",
                    guildId
            );
            return false;
        }

        try {
            scheduler.schedule(
                    () -> runQueuedProvisioning(guild, onProvisioned),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
            LOGGER.debug(
                    "[NoRule] Music command channel provisioning queued: guildId={} delaySeconds={}",
                    guildId,
                    delayMs / 1_000.0d
            );
            return true;
        } catch (RejectedExecutionException failure) {
            queuedGuilds.remove(guildId);
            logProvisioningFailure(guild, failure);
            return false;
        }
    }

    private void runQueuedProvisioning(Guild scheduledGuild,
                                       BiConsumer<Guild, TextChannel> onProvisioned) {
        long guildId = scheduledGuild.getIdLong();
        try {
            Guild currentGuild = scheduledGuild.getJDA().getGuildById(guildId);
            if (currentGuild == null) {
                logSkipped(guildId, "Guild is no longer available");
                queuedGuilds.remove(guildId);
                return;
            }
            if (!hasManageChannelPermission(currentGuild)) {
                logSkipped(guildId, "Missing permission: Manage Channels");
                queuedGuilds.remove(guildId);
                return;
            }

            ensureCommandChannel(currentGuild).whenComplete((channel, failure) -> {
                try {
                    if (failure != null) {
                        logProvisioningFailure(currentGuild, failure);
                    } else if (onProvisioned != null) {
                        onProvisioned.accept(currentGuild, channel);
                    }
                } finally {
                    queuedGuilds.remove(guildId);
                }
            });
        } catch (RuntimeException failure) {
            queuedGuilds.remove(guildId);
            logProvisioningFailure(scheduledGuild, failure);
        }
    }

    private CompletableFuture<TextChannel> startProvisioning(Guild guild) {
        TextChannel reusable = guild.getTextChannelsByName(DEFAULT_CHANNEL_NAME, true).stream()
                .filter(channel -> canUseForMusicPanel(guild, channel))
                .min(Comparator.comparingLong(TextChannel::getIdLong))
                .orElse(null);
        if (reusable != null) {
            rememberChannel(guild, reusable);
            logAlreadyProvisioned(guild, reusable);
            return CompletableFuture.completedFuture(reusable);
        }

        if (!hasManageChannelPermission(guild)) {
            return CompletableFuture.failedFuture(
                    new MissingManageChannelPermissionException()
            );
        }

        CompletableFuture<TextChannel> result = new CompletableFuture<>();
        guild.createTextChannel(DEFAULT_CHANNEL_NAME)
                .addMemberPermissionOverride(
                        guild.getSelfMember().getIdLong(),
                        Permission.getRaw(
                                Permission.VIEW_CHANNEL,
                                Permission.MESSAGE_SEND,
                                Permission.MESSAGE_EMBED_LINKS,
                                Permission.MESSAGE_HISTORY,
                                Permission.MESSAGE_MANAGE,
                                Permission.MESSAGE_ADD_REACTION
                        ),
                        0L
                )
                .queue(channel -> {
                    rememberChannel(guild, channel);
                    LOGGER.info(
                            "[NoRule] Music command channel created: guildId={} channelId={}",
                            guild.getIdLong(),
                            channel.getIdLong()
                    );
                    result.complete(channel);
                }, result::completeExceptionally);
        return result;
    }

    private TextChannel configuredChannel(Guild guild) {
        Long channelId = channelState.configuredChannelId(guild.getIdLong());
        if (channelId == null) {
            return null;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null || !canUseForMusicPanel(guild, channel)) {
            return null;
        }
        logAlreadyProvisioned(guild, channel);
        return channel;
    }

    private void rememberChannel(Guild guild, TextChannel channel) {
        long guildId = guild.getIdLong();
        channelState.rememberCommandChannel(guildId, channel.getIdLong());
        lastFailureLogAtByGuild.remove(guildId);
    }

    private boolean hasManageChannelPermission(Guild guild) {
        return guild.getSelfMember().hasPermission(Permission.MANAGE_CHANNEL);
    }

    private boolean canUseForMusicPanel(Guild guild, TextChannel channel) {
        return guild.getSelfMember().hasPermission(
                channel,
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS
        );
    }

    private void logAlreadyProvisioned(Guild guild, TextChannel channel) {
        LOGGER.debug(
                "[NoRule] Music command channel already provisioned: guildId={} channelId={}",
                guild.getIdLong(),
                channel.getIdLong()
        );
    }

    private void logSkipped(long guildId, String reason) {
        LOGGER.info(
                "[NoRule] Music command channel provisioning skipped: guildId={} reason={}",
                guildId,
                reason
        );
    }

    private Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String safeErrorMessage(Throwable failure) {
        Throwable cause = rootCause(failure);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    interface ChannelState {
        Long configuredChannelId(long guildId);

        void rememberCommandChannel(long guildId, long channelId);
    }

    private static final class OwnerChannelState implements ChannelState {
        private final MusicCommandService owner;

        private OwnerChannelState(MusicCommandService owner) {
            if (owner == null) {
                throw new IllegalArgumentException("owner cannot be null");
            }
            this.owner = owner;
        }

        @Override
        public Long configuredChannelId(long guildId) {
            return owner.settingsService().getMusic(guildId).getCommandChannelId();
        }

        @Override
        public void rememberCommandChannel(long guildId, long channelId) {
            Long configuredId = configuredChannelId(guildId);
            if (configuredId == null || configuredId != channelId) {
                owner.settingsService().updateSettings(
                        guildId,
                        settings -> settings.withMusic(settings.getMusic().withCommandChannelId(channelId))
                );
            }
            owner.musicService().rememberCommandChannel(guildId, channelId);
        }
    }

    private static final class MissingManageChannelPermissionException extends IllegalStateException {
        private MissingManageChannelPermissionException() {
            super("Missing permission: Manage Channels");
        }
    }
}
