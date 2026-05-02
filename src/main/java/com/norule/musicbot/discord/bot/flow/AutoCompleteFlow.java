package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;

public final class AutoCompleteFlow {
    private final MusicCommandService owner;

    public AutoCompleteFlow(MusicCommandService owner) {
        this.owner = owner;
    }

    public void run(CommandAutoCompleteInteractionEvent event) {
        owner.dispatchAutoCompleteFromGateway(event);
    }
}

