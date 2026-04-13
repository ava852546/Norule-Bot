package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class InteractionRouter {
    private final MusicCommandListener owner;

    InteractionRouter(MusicCommandListener owner) {
        this.owner = owner;
    }

    void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null) {
            event.reply("Guild only.").setEphemeral(true).queue();
            return;
        }

        String lang = owner.lang(event.getGuild().getIdLong());
        String commandName = owner.canonicalSlashName(event.getName());
        long remaining = owner.acquireCooldown(event.getUser().getIdLong());
        if (remaining > 0) {
            event.reply(owner.i18nService().t(lang, "general.command_cooldown",
                            Map.of("seconds", String.valueOf(owner.toCooldownSeconds(remaining)))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (owner.isSlashMusicCommand(commandName) && !owner.isMusicCommandChannelAllowed(event.getGuild(), event.getChannel().getIdLong())) {
            event.reply(owner.i18nService().t(lang, "music.command_channel_restricted")).setEphemeral(true).queue();
            return;
        }
        if (owner.isKnownSlashCommand(commandName)) {
            owner.logCommandUsage(event.getGuild(), event.getMember(), "/" + owner.buildSlashRoute(event), event.getChannel().getIdLong());
        }
        switch (commandName) {
            case "help" -> event.replyEmbeds(owner.helpEmbed(event.getGuild(), lang, "general").build())
                    .addComponents(
                            ActionRow.of(owner.helpMenu(lang)),
                            ActionRow.of(owner.helpButtonsPrimary(lang, "general")),
                            ActionRow.of(owner.helpButtonsSecondary(lang, "general"))
                    )
                    .setEphemeral(true)
                    .queue();
            case "ping" -> owner.handlePingSlash(event, lang);
            case "welcome" -> owner.handleWelcomeSlash(event, lang);
            case "number-chain" -> owner.settingsCommandHandler().handleSettingsNumberChain(event, lang);
            case "volume" -> owner.handleVolumeSlash(event, lang);
            case "history" -> event.replyEmbeds(owner.historyEmbed(event.getGuild(), lang).build()).queue();
            case "playlist" -> owner.handlePlaylistSlash(event, lang);
            case "join" -> {
                event.deferReply().queue();
                owner.handleJoin(event.getGuild(), event.getMember(),
                        text -> event.getHook().sendMessage(text)
                                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null), error -> {
                                }));
            }
            case "play" -> owner.handlePlaySlash(event, lang);
            case "skip" -> {
                event.deferReply().queue();
                owner.handleSkip(event.getGuild(),
                        text -> event.getHook().sendMessage(text)
                                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null), error -> {
                                }));
            }
            case "stop" -> {
                event.deferReply().queue();
                owner.handleStop(event.getGuild(),
                        text -> event.getHook().sendMessage(text)
                                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null), error -> {
                                }));
            }
            case "leave" -> {
                event.deferReply().queue();
                owner.handleLeave(event.getGuild(),
                        text -> event.getHook().sendMessage(text)
                                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null), error -> {
                                }));
            }
            case "music-panel" -> owner.musicPanelController().handlePanelSlashCommand(event, lang);
            case "repeat" -> {
                String mode = Objects.requireNonNull(event.getOption("mode")).getAsString();
                owner.setRepeat(event.getGuild(), mode);
                owner.refreshPanel(event.getGuild().getIdLong());
                TextChannel panelChannel = event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null;
                event.reply(owner.mapRepeatLabel(lang, owner.musicService().getRepeatMode(event.getGuild())))
                        .setEphemeral(true)
                        .queue(success -> owner.moveActivePanelToBottom(event.getGuild(), panelChannel), error -> {
                        });
            }
            case "music" -> owner.handleMusicSlash(event, lang);
            case "settings" -> owner.settingsCommandHandler().handleSettings(event, lang);
            case "private-room-settings" -> owner.handlePrivateRoomSettingsCommand(event, lang);
            case "delete-messages" -> owner.handleDeleteSlash(event, lang);
            case "warnings" -> owner.handleWarningsSlash(event, lang);
            case "anti-duplicate" -> owner.handleAntiDuplicateSlash(event, lang);
            case "ticket" -> {
                // handled by TicketListener
            }
            default -> event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }

    void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        String commandName = owner.canonicalSlashName(event.getName());
        if ("playlist".equals(commandName) && "name".equals(event.getFocusedOption().getName())) {
            owner.handlePlaylistAutocomplete(event);
        }
    }

    void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String componentId = event.getComponentId();

        if (MusicCommandListener.HELP_SELECT_ID.equals(componentId)) {
            String value = event.getValues().isEmpty() ? "general" : event.getValues().get(0);
            event.editMessageEmbeds(owner.helpEmbed(event.getGuild(), lang, value).build())
                    .setComponents(
                            ActionRow.of(owner.helpMenu(lang)),
                            ActionRow.of(owner.helpButtonsPrimary(lang, value)),
                            ActionRow.of(owner.helpButtonsSecondary(lang, value))
                    )
                    .queue();
            return;
        }

        if (owner.settingsCommandHandler().handleStringSelectInteraction(event, lang)) {
            return;
        }
        if (componentId.startsWith(MusicCommandListener.ROOM_SETTINGS_MENU_PREFIX)) {
            owner.handleRoomSettingsSelect(event, lang);
            return;
        }
        if (componentId.startsWith(MusicCommandListener.PLAY_PICK_PREFIX)) {
            String token = componentId.substring(MusicCommandListener.PLAY_PICK_PREFIX.length());
            MusicCommandListener.SearchRequest request = owner.searchRequests().remove(token);
            if (request == null) {
                event.reply(owner.i18nService().t(lang, "music.search_expired")).setEphemeral(true).queue();
                return;
            }
            if (Instant.now().isAfter(request.expiresAt)) {
                event.editMessage(owner.i18nService().t(lang, "music.search_expired")).setComponents(List.of()).queue();
                return;
            }
            if (event.getUser().getIdLong() != request.requestUserId) {
                event.reply(owner.i18nService().t(lang, "delete.only_requester")).setEphemeral(true).queue();
                return;
            }
            int index = Integer.parseInt(event.getValues().get(0));
            if (index < 0 || index >= request.results.size()) {
                event.reply(owner.i18nService().t(lang, "music.not_found", Map.of("query", request.query))).setEphemeral(true).queue();
                return;
            }
            AudioTrack picked = request.results.get(index);
            Member member = event.getMember();
            if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
                event.reply(owner.i18nService().t(lang, "music.join_first")).setEphemeral(true).queue();
                return;
            }
            AudioChannel memberChannel = member.getVoiceState().getChannel();
            AudioChannel botChannel = event.getGuild().getAudioManager().getConnectedChannel();
            if (botChannel != null && botChannel.getIdLong() != memberChannel.getIdLong()) {
                event.reply(owner.i18nService().t(lang, "music.join_bot_voice_channel",
                                Map.of("channel", botChannel.getAsMention())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            if (botChannel == null) {
                owner.musicService().joinChannel(event.getGuild(), memberChannel);
            }
            if (request.channelId != null) {
                owner.musicService().rememberCommandChannel(event.getGuild().getIdLong(), request.channelId);
            }
            String identifier = picked.getInfo().uri != null ? picked.getInfo().uri : picked.getInfo().title;
            String sourceLabel = owner.detectSource(picked);
            owner.musicService().queueTrackByIdentifier(
                    event.getGuild(),
                    identifier,
                    sourceLabel,
                    ignored -> owner.refreshPanel(event.getGuild().getIdLong()),
                    event.getUser().getIdLong(),
                    event.getUser().getName()
            );
            if (request.channelId != null) {
                TextChannel panelChannel = event.getGuild().getTextChannelById(request.channelId);
                if (panelChannel != null) {
                    owner.recreatePanelForChannel(event.getGuild(), panelChannel, lang);
                }
            }
            event.editMessage(owner.musicUx(lang, "queue_added", Map.of("title", picked.getInfo().title)))
                    .setComponents(List.of())
                    .queue();
        }
    }

    void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        if (event.getModalId().startsWith(MusicCommandListener.ROOM_LIMIT_MODAL_PREFIX) || event.getModalId().startsWith(MusicCommandListener.ROOM_RENAME_MODAL_PREFIX)) {
            owner.handleRoomSettingsModal(event);
            return;
        }
        if (owner.settingsCommandHandler().handleModalInteraction(event, lang)) {
            return;
        }
        if (event.getModalId().startsWith(MusicCommandListener.WARNING_REASON_MODAL_PREFIX)) {
            owner.handleWarningReasonModal(event, lang);
            return;
        }
        if (MusicCommandListener.WELCOME_MODAL_ID.equals(event.getModalId())) {
            owner.handleWelcomeModal(event, lang);
            return;
        }
    }

    void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String id = event.getComponentId();

        if (id.startsWith(MusicCommandListener.DELETE_CONFIRM_PREFIX) || id.startsWith(MusicCommandListener.DELETE_CANCEL_PREFIX)) {
            owner.handleDeleteButtons(event, lang);
            return;
        }
        if (owner.settingsCommandHandler().handleButtonInteraction(event, lang)) {
            return;
        }
        if (id.startsWith(MusicCommandListener.HELP_BUTTON_PREFIX)) {
            String category = id.substring(MusicCommandListener.HELP_BUTTON_PREFIX.length());
            event.editMessageEmbeds(owner.helpEmbed(event.getGuild(), lang, category).build())
                    .setComponents(
                            ActionRow.of(owner.helpMenu(lang)),
                            ActionRow.of(owner.helpButtonsPrimary(lang, category)),
                            ActionRow.of(owner.helpButtonsSecondary(lang, category))
                    )
                    .queue();
            return;
        }
        if (owner.musicPanelController().handlePanelButtonInteraction(event, lang)) {
            return;
        }
    }

    void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String componentId = event.getComponentId();
        if (owner.settingsCommandHandler().handleEntitySelectInteraction(event, lang)) {
            return;
        }
        if (componentId.startsWith(MusicCommandListener.ROOM_TRANSFER_SELECT_PREFIX)) {
            owner.handleRoomTransferSelect(event, lang);
        }
    }
}


