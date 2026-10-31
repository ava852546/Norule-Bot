package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelStateStore;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

class PrefixCommandRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrefixCommandRouter.class);
    private static final String KEY_UNKNOWN_COMMAND = "general.unknown_command";
    private static final String SUCCESS_REACTION = "\u2705";
    private static final String FAILURE_REACTION = "\u274C";

    private final MusicCommandService service;
    private final CommandCooldownService cooldownService;
    private final MusicMessageCleanupService messageCleanup;

    PrefixCommandRouter(MusicCommandService service, CommandCooldownService cooldownService) {
        this.service = service;
        this.cooldownService = cooldownService;
        this.messageCleanup = new MusicMessageCleanupService(service.scheduler(), message ->
                isActivePanel(message.getGuild().getIdLong(), message.getChannel().getIdLong(), message.getIdLong()),
                message -> service.musicPanelController().panelMode(message.getGuild()).modern());
    }

    void route(MessageReceivedEvent event) {
        String raw = event.getMessage().getContentRaw();
        if (!event.isFromGuild()) {
            return;
        }
        Guild guild = event.getGuild();
        if (service.honeypotService().isHoneypotChannel(guild.getIdLong(), event.getChannel().getIdLong())) {
            return;
        }
        String lang = service.lang(guild.getIdLong());
        RuntimeConfigSnapshot snapshot = service.runtimeConfigSnapshot();
        PrefixInvocation invocation = parseInvocation(raw, snapshot.getPrefix());
        boolean musicChannel = isMusicChannel(guild, event.getChannel().getIdLong());
        boolean prefixMusicCommand = invocation != null && isPrefixMusicCommand(invocation.command());
        if ((musicChannel && !isIdleAutoLeaveNotice(event, lang)) || prefixMusicCommand) {
            messageCleanup.enqueue(event.getMessage(), musicChannel ? "music-channel" : "prefix-music-command");
        }
        if (event.getAuthor().isBot() || invocation == null) {
            return;
        }
        String cmd = invocation.command();
        String arg = invocation.argument();

        if (isKnownPrefixCommand(cmd)) {
            long remaining = cooldownService.acquireCooldown(event.getAuthor().getIdLong());
            if (remaining > 0) {
                if (prefixMusicCommand) {
                    reactToCommand(event, false);
                }
                var reply = event.getChannel().sendMessage(service.i18nService().t(
                        lang,
                        "general.command_cooldown",
                        Map.of("seconds", String.valueOf(cooldownService.toCooldownSeconds(remaining)))
                ));
                if (musicChannel || prefixMusicCommand) {
                    reply.queue(message -> messageCleanup.enqueue(message, "prefix-cooldown-reply"));
                } else {
                    reply.queue();
                }
                return;
            }
        }

        if (isPrefixMusicCommand(cmd) && !service.isMusicCommandChannelAllowed(guild, event.getChannel().getIdLong())) {
            reactToCommand(event, false);
            event.getChannel().sendMessage(service.i18nService().t(lang, "music.command_channel_restricted"))
                    .queue(message -> messageCleanup.enqueue(message, "prefix-channel-restriction-reply"));
            return;
        }

        if (prefixMusicCommand) {
            reactToCommand(event, true);
        } else if (!isKnownPrefixCommand(cmd) && musicChannel) {
            reactToCommand(event, false);
        }

        switch (cmd) {
            case "help" -> service.helpCommandHandler().handleTextHelp(event.getChannel().asTextChannel(), guild, lang);
            case CommandNames.CMD_VOLUME -> service.playbackCommandHandler().handleTextCommand(event, guild, cmd, arg, lang);
            case CommandNames.CMD_HISTORY -> event.getChannel().sendMessageEmbeds(service.historyCommandHandler().historyEmbed(guild, lang).build()).queue();
            case CommandNames.CMD_PLAYLIST -> service.playlistCommandHandler().handlePlaylistPrefix(event, guild, arg, lang);
            case "join", "play", "skip", "stop", CommandNames.CMD_LEAVE, CommandNames.CMD_REPEAT -> service.playbackCommandHandler().handleTextCommand(event, guild, cmd, arg, lang);
            case CommandNames.CMD_MUSIC -> event.getChannel().sendMessageEmbeds(service.musicStatsCommandHandler().musicStatsEmbed(guild, lang).build()).queue();
            default -> event.getChannel().sendMessage(service.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).queue();
        }
        if (isKnownPrefixCommand(cmd)) {
            service.logCommandUsage(guild, event.getMember(), raw.trim(), event.getChannel().getIdLong());
        }
    }

    private boolean isMusicChannel(Guild guild, long channelId) {
        if (!service.musicPanelController().panelMode(guild).modern()) {
            return false;
        }
        Long configuredChannelId = service.settingsService().getMusic(guild.getIdLong()).getCommandChannelId();
        if (configuredChannelId != null && configuredChannelId == channelId) {
            return true;
        }
        MusicPanelStateStore.PanelRef panelRef = service.panelRefs().get(guild.getIdLong());
        return panelRef != null && panelRef.channelId == channelId;
    }

    private boolean isIdleAutoLeaveNotice(MessageReceivedEvent event, String lang) {
        return event.getAuthor().getIdLong() == event.getGuild().getSelfMember().getIdLong()
                && event.getMessage().getContentRaw().equals(
                service.i18nService().t(lang, "music.auto_leave_idle_notice")
        );
    }

    private void reactToCommand(MessageReceivedEvent event, boolean successful) {
        event.getMessage()
                .addReaction(Emoji.fromUnicode(successful ? SUCCESS_REACTION : FAILURE_REACTION))
                .queue(null, error -> LOGGER.debug(
                        "[NoRule] Failed to add prefix command reaction: guildId={} channelId={} messageId={}",
                        event.getGuild().getIdLong(),
                        event.getChannel().getIdLong(),
                        event.getMessageIdLong(),
                        error
                ));
    }

    void messageDeleted(long messageId) {
        messageCleanup.messageDeleted(messageId);
    }

    void close() {
        messageCleanup.close();
    }

    private boolean isActivePanel(long guildId, long channelId, long messageId) {
        MusicPanelStateStore.PanelRef panelRef = service.panelRefs().get(guildId);
        return panelRef != null && panelRef.channelId == channelId && panelRef.messageId == messageId;
    }

    static PrefixInvocation parseInvocation(String raw, String configuredPrefix) {
        if (raw == null) {
            return null;
        }
        String commandBody;
        if (configuredPrefix != null && !configuredPrefix.isEmpty() && raw.startsWith(configuredPrefix)) {
            commandBody = raw.substring(configuredPrefix.length());
        } else {
            return null;
        }
        String[] split = commandBody.trim().split("\\s+", 2);
        String command = split.length > 0 ? split[0].toLowerCase(Locale.ROOT) : "";
        if ("p".equals(command)) {
            command = CommandNames.CMD_PLAY;
        }
        String argument = split.length > 1 ? split[1].trim() : "";
        return new PrefixInvocation(command, argument);
    }

    record PrefixInvocation(String command, String argument) {
    }

    private boolean isKnownPrefixCommand(String cmd) {
        return "help".equals(cmd)
                || CommandNames.CMD_VOLUME.equals(cmd)
                || CommandNames.CMD_HISTORY.equals(cmd)
                || CommandNames.CMD_MUSIC.equals(cmd)
                || CommandNames.CMD_PLAYLIST.equals(cmd)
                || "join".equals(cmd)
                || "play".equals(cmd)
                || "skip".equals(cmd)
                || "stop".equals(cmd)
                || CommandNames.CMD_LEAVE.equals(cmd)
                || CommandNames.CMD_REPEAT.equals(cmd);
    }

    private boolean isPrefixMusicCommand(String cmd) {
        return "join".equals(cmd)
                || "play".equals(cmd)
                || "skip".equals(cmd)
                || "stop".equals(cmd)
                || CommandNames.CMD_LEAVE.equals(cmd)
                || CommandNames.CMD_REPEAT.equals(cmd)
                || CommandNames.CMD_VOLUME.equals(cmd)
                || CommandNames.CMD_HISTORY.equals(cmd)
                || CommandNames.CMD_MUSIC.equals(cmd)
                || CommandNames.CMD_PLAYLIST.equals(cmd);
    }
}
