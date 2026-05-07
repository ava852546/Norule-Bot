package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public final class ButtonFlow {
    private final MusicCommandService owner;
    private final ComponentInteractionRateGate rateGate;

    public ButtonFlow(MusicCommandService owner, ComponentInteractionRateGate rateGate) {
        this.owner = owner;
        this.rateGate = rateGate;
    }

    public void run(ButtonInteractionEvent event) {
        if (!rateGate.allow(event.getMessageIdLong())) {
            event.deferEdit().queue();
            return;
        }
        owner.dispatchButtonFromGateway(event);
    }
}

