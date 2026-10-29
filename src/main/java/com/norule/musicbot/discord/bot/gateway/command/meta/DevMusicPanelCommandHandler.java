package com.norule.musicbot.discord.bot.gateway.command.meta;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelRefreshService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;

/** Invoked only after the existing developer authorization check. */
public final class DevMusicPanelCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevMusicPanelCommandHandler.class);
    private final MusicCommandService owner;
    private final MusicPanelRefreshService panels;

    public DevMusicPanelCommandHandler(MusicCommandService owner, MusicPanelRefreshService panels) {
        this.owner = owner;
        this.panels = panels;
    }

    public CompletableFuture<String> execute(String argument) {
        if (argument == null || !argument.matches("[0-9]{1,20}")) {
            return CompletableFuture.completedFuture("Usage: &dev sendmusicpanel <channelId>");
        }
        try {
            long channelId = Long.parseUnsignedLong(argument);
            if (channelId == 0) {
                return CompletableFuture.completedFuture("[NoRule] Invalid channel ID.");
            }
            JDA jda = owner.currentJda();
            TextChannel channel = jda == null ? null : jda.getTextChannelById(channelId);
            if (channel == null) {
                LOGGER.warn("[NoRule] Music panel command failed: channelId={} reason=CHANNEL_UNAVAILABLE_OR_UNSUPPORTED", argument);
                return CompletableFuture.completedFuture("[NoRule] Channel does not exist, is inaccessible, or is not a supported text channel. The bot must be in its guild.");
            }
            return panels.sendDeveloperPanel(channel).exceptionally(failure -> {
                while (failure.getCause() != null && failure.getCause() != failure) {
                    failure = failure.getCause();
                }
                return "[NoRule] Music panel command failed: "
                        + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            });
        } catch (NumberFormatException invalidId) {
            return CompletableFuture.completedFuture("[NoRule] Invalid channel ID.");
        }
    }
}
