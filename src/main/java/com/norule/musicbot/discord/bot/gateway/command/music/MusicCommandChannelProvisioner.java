package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.domain.music.MusicCommandChannelProvisioning;
import com.norule.musicbot.service.music.MusicCommandChannelProvisioningService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class MusicCommandChannelProvisioner {
    public static final String DEFAULT_CHANNEL_NAME = "norule-music";
    public static final long STARTUP_INTERVAL_MS = 1_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicCommandChannelProvisioner.class);

    private final ChannelState channelState;
    private final ScheduledExecutorService scheduler;
    private final long startupIntervalMs;
    private final MusicCommandChannelProvisioningService provisioningState;

    public MusicCommandChannelProvisioner(MusicCommandService owner, ScheduledExecutorService scheduler,
                                         MusicCommandChannelProvisioningService provisioningState) {
        this(new OwnerChannelState(owner), scheduler, STARTUP_INTERVAL_MS, provisioningState);
    }

    MusicCommandChannelProvisioner(ChannelState channelState,
                                   ScheduledExecutorService scheduler,
                                   long startupIntervalMs,
                                   MusicCommandChannelProvisioningService provisioningState) {
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
        this.provisioningState = Objects.requireNonNull(provisioningState);
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

    public boolean queueGuildJoinProvisioning(Guild guild,
                                               BiConsumer<Guild, TextChannel> onProvisioned) {
        return queueProvisioning(guild, startupIntervalMs, onProvisioned);
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

        try {
            MusicCommandChannelProvisioning attempt = provisioningState.tryStart(guild.getIdLong());
            return attempt == null
                    ? CompletableFuture.failedFuture(new AlreadyAttemptedException())
                    : executeProvisioning(guild, attempt);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
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
        if (cause instanceof AlreadyAttemptedException) {
            return;
        }
        LOGGER.warn(
                "Music command channel resolution failed: guildId={} reason={}",
                guild.getIdLong(),
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
        MusicCommandChannelProvisioning attempt;
        try {
            attempt = provisioningState.tryStart(guildId);
        } catch (RuntimeException failure) {
            // Fail closed: Discord must not be called without a durable claim.
            logProvisioningFailure(guild, failure);
            return false;
        }
        if (attempt == null) {
            return false;
        }

        try {
            scheduler.schedule(
                    () -> runQueuedProvisioning(guild, attempt, onProvisioned),
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
            provisioningState.fail(attempt, failure);
            return false;
        }
    }

    private void runQueuedProvisioning(Guild scheduledGuild,
                                       MusicCommandChannelProvisioning attempt,
                                       BiConsumer<Guild, TextChannel> onProvisioned) {
        long guildId = scheduledGuild.getIdLong();
        try {
            if (!provisioningState.isAttempting(attempt)) {
                return;
            }
            Guild currentGuild = scheduledGuild.getJDA().getGuildById(guildId);
            if (currentGuild == null) {
                provisioningState.fail(attempt, new IllegalStateException("Guild is no longer available"));
                return;
            }

            executeProvisioning(currentGuild, attempt).thenAccept(channel -> {
                if (channel != null && onProvisioned != null) {
                    onProvisioned.accept(currentGuild, channel);
                }
            }).exceptionally(failure -> {
                logProvisioningFailure(currentGuild, failure);
                return null;
            });
        } catch (RuntimeException failure) {
            provisioningState.fail(attempt, failure);
        }
    }

    public void guildLeft(long guildId) {
        provisioningState.guildLeft(guildId);
    }

    private CompletableFuture<TextChannel> executeProvisioning(Guild guild,
                                                               MusicCommandChannelProvisioning attempt) {
        CompletableFuture<TextChannel> creation;
        try {
            if (!provisioningState.isAttempting(attempt)) {
                return CompletableFuture.failedFuture(new AlreadyAttemptedException());
            }
            TextChannel configured = configuredChannel(guild);
            creation = configured != null ? CompletableFuture.completedFuture(configured) : startProvisioning(guild);
        } catch (RuntimeException failure) {
            creation = CompletableFuture.failedFuture(failure);
        }
        return creation.thenApply(channel -> {
            // A callback from an earlier membership must not affect a new attempt.
            if (!provisioningState.isAttempting(attempt)) {
                throw new AlreadyAttemptedException();
            }
            rememberChannel(guild, channel);
            if (!provisioningState.succeed(attempt)) {
                throw new AlreadyAttemptedException();
            }
            return channel;
        }).whenComplete((channel, failure) -> {
            if (failure != null) {
                provisioningState.fail(attempt, failure);
            }
        });
    }

    private CompletableFuture<TextChannel> startProvisioning(Guild guild) {
        TextChannel reusable = guild.getTextChannelsByName(DEFAULT_CHANNEL_NAME, true).stream()
                .filter(channel -> canUseForMusicPanel(guild, channel))
                .min(Comparator.comparingLong(TextChannel::getIdLong))
                .orElse(null);
        if (reusable != null) {
            logAlreadyProvisioned(guild, reusable);
            return CompletableFuture.completedFuture(reusable);
        }

        if (!hasManageChannelPermission(guild)) {
            return CompletableFuture.failedFuture(
                    new MissingManageChannelPermissionException()
            );
        }
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_PERMISSIONS)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Missing permission: MANAGE_PERMISSIONS"));
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
            super("Missing permission: MANAGE_CHANNEL");
        }
    }

    private static final class AlreadyAttemptedException extends IllegalStateException {
        private AlreadyAttemptedException() {
            super("Music command channel provisioning already attempted for this membership");
        }
    }
}
