package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicCommandChannelProvisioner;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

public final class MusicPanelController {
    private final MusicCommandService owner;
    private final MusicCommandChannelProvisioner commandChannelProvisioner;
    private final LongConsumer panelRefresher;
    public MusicPanelController(MusicCommandService owner,
                                MusicCommandChannelProvisioner commandChannelProvisioner,
                                LongConsumer panelRefresher) {
        this.owner = owner;
        this.commandChannelProvisioner = commandChannelProvisioner;
        this.panelRefresher = panelRefresher;
    }

    public void ensurePanelForChannel(Guild guild, TextChannel channel, String lang) {
        owner.createPanelMessageWithFeedback(guild, channel, lang, () -> {
        }, error -> {
        });
    }

    public CompletableFuture<TextChannel> resolveOrCreateCommandChannel(Guild guild, TextChannel fallback) {
        return commandChannelProvisioner.ensureCommandChannel(guild).handle((channel, failure) -> {
            if (failure != null) {
                commandChannelProvisioner.logProvisioningFailure(guild, failure);
                return commandChannelProvisioner.adoptCommandChannel(guild, fallback) ? fallback : null;
            }
            return channel;
        });
    }

    public void initializeGuild(Guild guild) {
        if (guild == null) {
            return;
        }
        commandChannelProvisioner.queueGuildJoinProvisioning(guild, this::initializePanel);
    }

    public void initializeGuilds(List<Guild> guilds) {
        commandChannelProvisioner.queueStartupProvisioning(guilds, this::initializePanel);
    }

    private void initializePanel(Guild guild, TextChannel channel) {
        ensurePanelForChannel(guild, channel, owner.lang(guild.getIdLong()));
    }

    public void moveActivePanelToBottom(Guild guild, TextChannel preferredChannel) {
        if (guild == null) {
            return;
        }
        MusicPanelStateStore.PanelRef active = owner.panelRefs().get(guild.getIdLong());
        if (active == null) {
            return;
        }
        TextChannel activeChannel = guild.getTextChannelById(active.channelId);
        if (activeChannel == null || !activeChannel.canTalk()) {
            return;
        }
        if (preferredChannel != null) {
            owner.musicService().rememberCommandChannel(guild.getIdLong(), preferredChannel.getIdLong());
        }
        panelRefresher.accept(guild.getIdLong());
    }

    public void refreshPanel(long guildId) {
        panelRefresher.accept(guildId);
    }
    public void handlePanelSlashCommand(SlashCommandInteractionEvent event, String lang) {
        if (!event.isFromGuild() || event.getGuild() == null) {
            event.reply(owner.musicText(lang, "panel_text_channel_only"))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        TextChannel fallback = event.getChannel() instanceof TextChannel textChannel ? textChannel : null;
        event.deferReply(true).queue(hook -> resolveOrCreateCommandChannel(event.getGuild(), fallback)
                .thenAccept(panelChannel -> {
                    if (panelChannel == null) {
                        hook.editOriginal(owner.musicText(lang, "panel_text_channel_only")).queue();
                        return;
                    }
                    owner.createPanelMessageWithFeedback(event.getGuild(), panelChannel, lang,
                            () -> hook.editOriginal(owner.musicText(
                                    lang,
                                    "panel_ready",
                                    Map.of("channel", panelChannel.getAsMention())
                            )).queue(),
                            error -> hook.editOriginal(owner.musicText(
                                    lang,
                                    "panel_update_failed",
                                    Map.of("error", error)
                            )).queue());
                }));
    }
    public boolean handlePanelButtonInteraction(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        if (!isPanelButton(id)) {
            return false;
        }

        Guild guild = event.getGuild();
        if (guild == null || !(event.getChannel() instanceof TextChannel channel)) {
            return true;
        }

        MusicPanelStateStore.PanelRef active = owner.panelRefs().get(guild.getIdLong());
        if (active == null || active.channelId != channel.getIdLong() || active.messageId != event.getMessageIdLong()) {
            event.reply(owner.i18nService().t(lang, "music.panel_stale")).setEphemeral(true)
                    .queue(success -> {
                        if (active == null) {
                            ensurePanelForChannel(guild, channel, lang);
                        }
                    });
            return true;
        }
        if (!owner.canControlPanel(guild, event.getMember())) {
            event.reply(owner.i18nService().t(lang, "music.panel_same_voice_only")).setEphemeral(true).queue();
            return true;
        }
        long remaining = owner.acquirePanelButtonCooldown(event.getUser().getIdLong());
        if (remaining > 0L) {
            event.reply(owner.i18nService().t(lang, "general.command_cooldown",
                            Map.of("seconds", String.valueOf(owner.toCooldownSeconds(remaining)))))
                    .setEphemeral(true)
                    .queue();
            return true;
        }

        switch (id) {
            case MusicCommandService.PANEL_PLAY_PAUSE -> {
                event.deferEdit().queue();
                owner.musicService().togglePause(guild);
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandService.PANEL_SKIP -> {
                event.deferEdit().queue();
                owner.musicService().skip(guild);
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandService.PANEL_STOP -> {
                event.deferEdit().queue();
                owner.musicService().stop(guild);
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandService.PANEL_LEAVE -> {
                event.deferEdit().queue();
                owner.musicService().stop(guild);
                owner.musicService().leaveChannel(guild);
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandService.PANEL_REPEAT_TOGGLE -> {
                event.deferEdit().queue();
                owner.setRepeat(guild, nextRepeatMode(owner.musicService().getRepeatMode(guild)));
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandService.PANEL_AUTOPLAY_TOGGLE -> {
                event.deferEdit().queue();
                owner.toggleAutoplay(guild.getIdLong());
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandService.PANEL_VOLUME_DOWN -> {
                event.deferEdit().queue();
                owner.adjustPanelVolume(guild, -10);
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true, true);
            }
            case MusicCommandService.PANEL_VOLUME_UP -> {
                event.deferEdit().queue();
                owner.adjustPanelVolume(guild, 10);
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true, true);
            }
            case MusicCommandService.PANEL_REFRESH -> {
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true);
            }
            case MusicCommandService.PANEL_SHUFFLE -> {
                if (owner.musicService().getQueueSnapshot(guild).isEmpty()) {
                    event.reply(owner.i18nService().t(lang, "music.queue_empty")).setEphemeral(true).queue();
                    return true;
                }
                event.deferEdit().queue();
                owner.musicService().shuffleQueue(guild);
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true);
            }
            default -> {
                // No-op: non-panel buttons are filtered by caller/router.
            }
        }
        return true;
    }

    private boolean isPanelButton(String componentId) {
        return List.of(
                MusicCommandService.PANEL_PLAY_PAUSE,
                MusicCommandService.PANEL_SKIP,
                MusicCommandService.PANEL_STOP,
                MusicCommandService.PANEL_LEAVE,
                MusicCommandService.PANEL_REPEAT_TOGGLE,
                MusicCommandService.PANEL_AUTOPLAY_TOGGLE,
                MusicCommandService.PANEL_VOLUME_DOWN,
                MusicCommandService.PANEL_VOLUME_UP,
                MusicCommandService.PANEL_REFRESH,
                MusicCommandService.PANEL_SHUFFLE
        ).contains(componentId);
    }

    private String nextRepeatMode(String currentMode) {
        if ("OFF".equalsIgnoreCase(currentMode)) {
            return "SINGLE";
        }
        if ("SINGLE".equalsIgnoreCase(currentMode)) {
            return "ALL";
        }
        return "OFF";
    }
}






