package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.routing.DiscordCommandRouteMapper;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;

public final class AutoCompleteOps {
    private final MusicCommandService owner;
    private final DiscordCommandRouteMapper routeMapper = new DiscordCommandRouteMapper();

    public AutoCompleteOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handle(CommandAutoCompleteInteractionEvent event) {
        String commandName = routeMapper.canonicalSlashName(event.getName());
        if ("playlist".equals(commandName) && "name".equals(event.getFocusedOption().getName())) {
            owner.playlistCommandHandler().handlePlaylistAutocomplete(event);
        }
    }
}

