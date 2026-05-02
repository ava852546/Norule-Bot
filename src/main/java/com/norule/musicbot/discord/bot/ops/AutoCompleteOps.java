package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;

public final class AutoCompleteOps {
    private final MusicCommandService owner;

    public AutoCompleteOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handle(CommandAutoCompleteInteractionEvent event) {
        String commandName = owner.canonicalSlashName(event.getName());
        if ("playlist".equals(commandName) && "name".equals(event.getFocusedOption().getName())) {
            owner.playlistCommandHandler().handlePlaylistAutocomplete(event);
        }
    }
}

