package com.norule.musicbot.discord.listeners;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.util.Map;

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
        if (!owner.isBotReadyForSlashCommands()) {
            event.reply(owner.i18nService().t(lang, "general.bot_starting_up"))
                    .setEphemeral(true)
                    .queue();
            return;
        }
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
            case "help" -> owner.helpCommandHandler().handleHelpSlash(event, lang);
            case "ping" -> owner.pingCommandHandler().handlePingSlash(event, lang);
            case "welcome" -> owner.welcomeCommandHandler().handleWelcomeSlash(event, lang);
            case "number-chain" -> owner.settingsCommandHandler().handleSettingsNumberChain(event, lang);
            case "volume" -> owner.playbackCommandHandler().handleVolumeSlash(event, lang);
            case "history" -> owner.historyCommandHandler().handleHistorySlash(event, lang);
            case "playlist" -> owner.playlistCommandHandler().handlePlaylistSlash(event, lang);
            case "join" -> owner.playbackCommandHandler().handleJoinSlash(event);
            case "play" -> owner.playbackCommandHandler().handlePlaySlash(event, lang);
            case "skip" -> owner.playbackCommandHandler().handleSkipSlash(event);
            case "stop" -> owner.playbackCommandHandler().handleStopSlash(event);
            case "leave" -> owner.playbackCommandHandler().handleLeaveSlash(event);
            case "music-panel" -> owner.musicPanelController().handlePanelSlashCommand(event, lang);
            case "repeat" -> owner.playbackCommandHandler().handleRepeatSlash(event, lang);
            case "music" -> owner.handleMusicSlash(event, lang);
            case "settings" -> owner.settingsCommandHandler().handleSettings(event, lang);
            case "private-room-settings" -> owner.handlePrivateRoomSettingsCommand(event, lang);
            case "delete-messages" -> owner.handleDeleteSlash(event, lang);
            case "warnings" -> owner.handleWarningsSlash(event, lang);
            case "anti-duplicate" -> owner.handleAntiDuplicateSlash(event, lang);
            case "honeypot-channel" -> owner.honeypotCommandHandler().handleCreateSlash(event, lang);
            case "user-info" -> owner.infoCommandHandler().handleUserInfo(event, lang);
            case "role-info" -> owner.infoCommandHandler().handleRoleInfo(event, lang);
            case "server-info" -> owner.infoCommandHandler().handleServerInfo(event, lang);
            case "ticket" -> {
                // handled by TicketListener
            }
            default -> event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }

    void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        String commandName = owner.canonicalSlashName(event.getName());
        if ("playlist".equals(commandName) && "name".equals(event.getFocusedOption().getName())) {
            owner.playlistCommandHandler().handlePlaylistAutocomplete(event);
        }
    }

    void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String componentId = event.getComponentId();

        if (MusicCommandListener.HELP_SELECT_ID.equals(componentId)) {
            owner.helpCommandHandler().handleHelpSelect(event, lang);
            return;
        }

        if (owner.settingsCommandHandler().handleStringSelectInteraction(event, lang)) {
            return;
        }
        if (componentId.startsWith(MusicCommandListener.ROOM_SETTINGS_MENU_PREFIX)) {
            owner.handleRoomSettingsSelect(event, lang);
            return;
        }
        if (componentId.startsWith(MusicPlaybackCommandHandler.PLAY_PICK_PREFIX)) {
            owner.playbackCommandHandler().handlePlayPick(event, lang);
            return;
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
            owner.welcomeCommandHandler().handleWelcomeModal(event, lang);
            return;
        }
    }

    void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith(MusicCommandListener.DEV_INFO_REFRESH_BUTTON_PREFIX)) {
            owner.handleDeveloperInfoRefreshButton(event);
            return;
        }
        if (id.startsWith(MusicCommandListener.DEV_GUILDS_BUTTON_PREFIX)) {
            owner.handleDeveloperGuildsButton(event);
            return;
        }
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());

        if (id.startsWith(MusicCommandListener.DELETE_CONFIRM_PREFIX) || id.startsWith(MusicCommandListener.DELETE_CANCEL_PREFIX)) {
            owner.handleDeleteButtons(event, lang);
            return;
        }
        if (id.startsWith(PlaylistCommandHandler.LIST_BUTTON_PREFIX)) {
            owner.playlistCommandHandler().handlePlaylistListButtons(event, lang);
            return;
        }
        if (id.startsWith(HistoryCommandHandler.HISTORY_BUTTON_PREFIX)) {
            owner.historyCommandHandler().handleHistoryButtons(event, lang);
            return;
        }
        if (id.startsWith(PlaylistCommandHandler.VIEW_BUTTON_PREFIX)) {
            owner.playlistCommandHandler().handlePlaylistViewButtons(event, lang);
            return;
        }
        if (id.startsWith(PlaylistCommandHandler.TRACK_REMOVE_CONFIRM_PREFIX)
                || id.startsWith(PlaylistCommandHandler.TRACK_REMOVE_CANCEL_PREFIX)) {
            owner.playlistCommandHandler().handlePlaylistTrackRemoveButtons(event, lang);
            return;
        }
        if (owner.settingsCommandHandler().handleButtonInteraction(event, lang)) {
            return;
        }
        if (id.startsWith(MusicCommandListener.HELP_BUTTON_PREFIX)) {
            owner.helpCommandHandler().handleHelpButton(event, lang);
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


