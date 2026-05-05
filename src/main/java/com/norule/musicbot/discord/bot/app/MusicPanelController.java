package com.norule.musicbot.discord.bot.app;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

import java.util.List;
import java.util.Map;

public final class MusicPanelController {
    private final MusicCommandService owner;
    public MusicPanelController(MusicCommandService owner) {
        this.owner = owner;
    }

    void recreatePanelForChannel(Guild guild, TextChannel channel, String lang) {
        long guildId = guild.getIdLong();
        MusicCommandService.PanelRef old = owner.panelRefs().remove(guildId);
        if (old != null) {
            TextChannel oldChannel = guild.getTextChannelById(old.channelId);
            if (oldChannel != null) {
                oldChannel.deleteMessageById(old.messageId).queue(success -> {
                }, error -> {
                });
            }
        }
        owner.createPanelMessageWithFeedback(guild, channel, lang, () -> {
        }, error -> {
        });
    }

    void moveActivePanelToBottom(Guild guild, TextChannel preferredChannel) {
        if (guild == null) {
            return;
        }
        MusicCommandService.PanelRef active = owner.panelRefs().get(guild.getIdLong());
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
        owner.refreshPanel(guild.getIdLong());
    }
    public void handlePanelSlashCommand(SlashCommandInteractionEvent event, String lang) {
        event.deferReply().queue();
        if (!event.isFromGuild() || event.getGuild() == null || event.getChannelType().isThread()) {
            event.getHook().sendMessage("Music panel can only be created in a text channel.").setEphemeral(true).queue();
            return;
        }
        if (!(event.getChannel() instanceof TextChannel textChannel)) {
            event.getHook().sendMessage("Music panel can only be created in a text channel.").setEphemeral(true).queue();
            return;
        }
        if (!textChannel.canTalk()) {
            event.getHook().sendMessage("I cannot send messages in this channel. Check bot permissions.").setEphemeral(true).queue();
            return;
        }
        owner.createPanelMessageWithFeedback(event.getGuild(), textChannel, lang,
                () -> event.getHook().sendMessage(owner.i18nService().t(lang, "music.panel_title")).setEphemeral(true).queue(),
                error -> event.getHook().sendMessage("Failed to create/update panel: " + error).setEphemeral(true).queue());
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

        MusicCommandService.PanelRef active = owner.panelRefs().get(guild.getIdLong());
        if (active == null || active.channelId != channel.getIdLong() || active.messageId != event.getMessageIdLong()) {
            event.reply(owner.i18nService().t(lang, "music.panel_stale")).setEphemeral(true)
                    .queue(success -> recreatePanelForChannel(guild, channel, lang), error -> recreatePanelForChannel(guild, channel, lang));
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
                Member operatorMember = event.getMember();
                String operator = operatorMember == null ? event.getUser().getAsMention() : operatorMember.getAsMention();
                channel.sendMessage(owner.i18nService().t(lang, "music.left_by_operator", Map.of("user", operator)))
                        .queue(success -> {
                        }, error -> {
                        });
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






