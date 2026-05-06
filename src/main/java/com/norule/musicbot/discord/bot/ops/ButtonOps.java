package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.HistoryCommandHandler;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.app.PlaylistCommandHandler;
import com.norule.musicbot.discord.bot.ops.stats.StatsOps;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;
import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public final class ButtonOps {
    private final MusicCommandService owner;
    private final StatsOps statsOps;
    private final WordChainOps wordChainOps;

    public ButtonOps(MusicCommandService owner, WordChainOps wordChainOps) {
        this.owner = owner;
        this.statsOps = new StatsOps(owner);
        this.wordChainOps = wordChainOps;
    }

    public void handle(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (event.getGuild() == null) {
            return;
        }
        TicketOps ticketOps = owner.ticketOps();
        if (ticketOps != null && ticketOps.handleButton(event)) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        if (wordChainOps != null && wordChainOps.handleButton(event, lang)) {
            return;
        }
        if (statsOps.handleButton(event)) {
            return;
        }
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

