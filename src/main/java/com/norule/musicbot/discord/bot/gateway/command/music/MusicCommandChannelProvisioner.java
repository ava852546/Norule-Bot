package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MusicCommandChannelProvisioner {
    public static final String DEFAULT_CHANNEL_NAME = "norule-music";

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicCommandChannelProvisioner.class);
    private static final long FAILURE_LOG_COOLDOWN_MS = Duration.ofMinutes(10).toMillis();

    private final MusicCommandService owner;
    private final Map<Long, CompletableFuture<TextChannel>> provisioningByGuild = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastFailureLogAtByGuild = new ConcurrentHashMap<>();

    public MusicCommandChannelProvisioner(MusicCommandService owner) {
        this.owner = owner;
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
                safeErrorMessage(failure)
        );
    }

    private CompletableFuture<TextChannel> startProvisioning(Guild guild) {
        TextChannel reusable = guild.getTextChannelsByName(DEFAULT_CHANNEL_NAME, true).stream()
                .filter(channel -> canUseForMusicPanel(guild, channel))
                .min(Comparator.comparingLong(TextChannel::getIdLong))
                .orElse(null);
        if (reusable != null) {
            rememberChannel(guild, reusable);
            return CompletableFuture.completedFuture(reusable);
        }

        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_CHANNEL)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Missing permission: " + Permission.MANAGE_CHANNEL.getName())
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
        Long channelId = owner.settingsService().getMusic(guild.getIdLong()).getCommandChannelId();
        if (channelId == null) {
            return null;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        return channel != null && canUseForMusicPanel(guild, channel) ? channel : null;
    }

    private void rememberChannel(Guild guild, TextChannel channel) {
        long guildId = guild.getIdLong();
        Long configuredId = owner.settingsService().getMusic(guildId).getCommandChannelId();
        if (configuredId == null || configuredId != channel.getIdLong()) {
            owner.settingsService().updateSettings(
                    guildId,
                    settings -> settings.withMusic(settings.getMusic().withCommandChannelId(channel.getIdLong()))
            );
        }
        owner.musicService().rememberCommandChannel(guildId, channel.getIdLong());
        lastFailureLogAtByGuild.remove(guildId);
    }

    private boolean canUseForMusicPanel(Guild guild, TextChannel channel) {
        return guild.getSelfMember().hasPermission(
                channel,
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS
        );
    }

    private String safeErrorMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
