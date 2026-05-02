package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

public final class SelectFlow {
    private final MusicCommandService owner;

    public SelectFlow(MusicCommandService owner) {
        this.owner = owner;
    }

    public void runString(StringSelectInteractionEvent event) {
        owner.dispatchStringSelectFromGateway(event);
    }

    public void runEntity(EntitySelectInteractionEvent event) {
        owner.dispatchEntitySelectFromGateway(event);
    }
}

