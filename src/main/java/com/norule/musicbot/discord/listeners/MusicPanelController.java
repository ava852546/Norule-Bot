package com.norule.musicbot.discord.listeners;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

import java.util.List;
import java.util.Map;

final class MusicPanelController {
    private final MusicCommandListener owner;

    MusicPanelController(MusicCommandListener owner) {
        this.owner = owner;
    }

    void recreatePanelForChannel(Guild guild, TextChannel channel, String lang) {
        long guildId = guild.getIdLong();
        MusicCommandListener.PanelRef old = owner.panelRefs().remove(guildId);
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
        MusicCommandListener.PanelRef active = owner.panelRefs().get(guild.getIdLong());
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

    void handlePanelSlashCommand(SlashCommandInteractionEvent event, String lang) {
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

    boolean handlePanelButtonInteraction(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        if (!isPanelButton(id)) {
            return false;
        }

        Guild guild = event.getGuild();
        if (guild == null || !(event.getChannel() instanceof TextChannel channel)) {
            return true;
        }

        MusicCommandListener.PanelRef active = owner.panelRefs().get(guild.getIdLong());
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
            case MusicCommandListener.PANEL_PLAY_PAUSE -> {
                owner.musicService().togglePause(guild);
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandListener.PANEL_SKIP -> {
                owner.musicService().skip(guild);
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandListener.PANEL_STOP -> {
                owner.musicService().stop(guild);
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandListener.PANEL_LEAVE -> {
                owner.musicService().stop(guild);
                owner.musicService().leaveChannel(guild);
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
                Member operatorMember = event.getMember();
                String operator = operatorMember == null ? event.getUser().getAsMention() : operatorMember.getAsMention();
                channel.sendMessage(owner.i18nService().t(lang, "music.left_by_operator", Map.of("user", operator)))
                        .queue(success -> {
                        }, error -> {
                        });
            }
            case MusicCommandListener.PANEL_REPEAT_TOGGLE -> {
                owner.setRepeat(guild, nextRepeatMode(owner.musicService().getRepeatMode(guild)));
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandListener.PANEL_AUTOPLAY_TOGGLE -> {
                owner.toggleAutoplay(guild.getIdLong());
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), false);
            }
            case MusicCommandListener.PANEL_VOLUME_DOWN -> {
                owner.adjustPanelVolume(guild, -10);
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true, true);
            }
            case MusicCommandListener.PANEL_VOLUME_UP -> {
                owner.adjustPanelVolume(guild, 10);
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true, true);
            }
            case MusicCommandListener.PANEL_REFRESH -> {
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true);
            }
            case MusicCommandListener.PANEL_SHUFFLE -> {
                if (owner.musicService().getQueueSnapshot(guild).isEmpty()) {
                    event.reply(owner.i18nService().t(lang, "music.queue_empty")).setEphemeral(true).queue();
                    return true;
                }
                owner.musicService().shuffleQueue(guild);
                event.deferEdit().queue();
                owner.refreshPanelMessage(guild, channel, event.getMessageIdLong(), true);
            }
            default -> {
            }
        }
        return true;
    }

    private boolean isPanelButton(String componentId) {
        return List.of(
                MusicCommandListener.PANEL_PLAY_PAUSE,
                MusicCommandListener.PANEL_SKIP,
                MusicCommandListener.PANEL_STOP,
                MusicCommandListener.PANEL_LEAVE,
                MusicCommandListener.PANEL_REPEAT_TOGGLE,
                MusicCommandListener.PANEL_AUTOPLAY_TOGGLE,
                MusicCommandListener.PANEL_VOLUME_DOWN,
                MusicCommandListener.PANEL_VOLUME_UP,
                MusicCommandListener.PANEL_REFRESH,
                MusicCommandListener.PANEL_SHUFFLE
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


