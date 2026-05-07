package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

public final class SelectFlow {
    private final MusicCommandService owner;
    private final ComponentInteractionRateGate rateGate;

    public SelectFlow(MusicCommandService owner, ComponentInteractionRateGate rateGate) {
        this.owner = owner;
        this.rateGate = rateGate;
    }

    public void runString(StringSelectInteractionEvent event) {
        if (!rateGate.allow(event.getMessageIdLong())) {
            event.deferEdit().queue();
            return;
        }
        owner.dispatchStringSelectFromGateway(event);
    }

    public void runEntity(EntitySelectInteractionEvent event) {
        if (!rateGate.allow(event.getMessageIdLong())) {
            event.deferEdit().queue();
            return;
        }
        owner.dispatchEntitySelectFromGateway(event);
    }
}

