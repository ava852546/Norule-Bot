package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.HistoryCommandHandler;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.app.PlaylistCommandHandler;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public final class ButtonOps {
    private final MusicCommandService owner;

    public ButtonOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handle(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith(MusicCommandService.DEV_INFO_REFRESH_BUTTON_PREFIX)) {
            owner.handleDeveloperInfoRefreshButton(event);
            return;
        }
        if (id.startsWith(MusicCommandService.DEV_GUILDS_BUTTON_PREFIX)) {
            owner.handleDeveloperGuildsButton(event);
            return;
        }
        if (event.getGuild() == null) {
            return;
        }
        TicketOps ticketOps = owner.ticketOps();
        if (ticketOps != null && ticketOps.handleButton(event)) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        if (id.startsWith(MusicCommandService.DELETE_CONFIRM_PREFIX) || id.startsWith(MusicCommandService.DELETE_CANCEL_PREFIX)) {
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
        if (id.startsWith(MusicCommandService.HELP_BUTTON_PREFIX)) {
            owner.helpCommandHandler().handleHelpButton(event, lang);
            return;
        }
        owner.musicPanelController().handlePanelButtonInteraction(event, lang);
    }
}

