package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackCommandHandler;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.discord.bot.ops.stats.StatsOps;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

public final class SelectOps {
    private final MusicCommandService owner;
    private final StatsOps statsOps;

    public SelectOps(MusicCommandService owner) {
        this.owner = owner;
        this.statsOps = new StatsOps(owner);
    }

    public void handleString(StringSelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String componentId = event.getComponentId();
        TicketOps ticketOps = owner.ticketOps();
        if (ticketOps != null && ticketOps.handleStringSelect(event)) {
            return;
        }
        if (statsOps.handleStringSelect(event)) {
            return;
        }
        if (MusicCommandService.HELP_SELECT_ID.equals(componentId)) {
            owner.helpCommandHandler().handleHelpSelect(event, lang);
            return;
        }
        if (owner.settingsCommandHandler().handleStringSelectInteraction(event, lang)) {
            return;
        }
        if (componentId.startsWith(ComponentIds.ROOM_SETTINGS_MENU_PREFIX)) {
            owner.privateRoomSettingsCommandHandler().handleRoomSettingsSelect(event, lang);
            return;
        }
        if (componentId.startsWith(MusicPlaybackCommandHandler.PLAY_PICK_PREFIX)) {
            owner.playbackCommandHandler().handlePlayPick(event, lang);
        }
    }

    public void handleEntity(EntitySelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String componentId = event.getComponentId();
        TicketOps ticketOps = owner.ticketOps();
        if (ticketOps != null && ticketOps.handleEntitySelect(event)) {
            return;
        }
        if (owner.settingsCommandHandler().handleEntitySelectInteraction(event, lang)) {
            return;
        }
        if (componentId.startsWith(ComponentIds.ROOM_TRANSFER_SELECT_PREFIX)) {
            owner.privateRoomSettingsCommandHandler().handleRoomTransferSelect(event, lang);
        }
    }
}

