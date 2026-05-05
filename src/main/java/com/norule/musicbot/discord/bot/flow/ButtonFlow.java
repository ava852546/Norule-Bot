package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public final class ButtonFlow {
    private final MusicCommandService owner;

    public ButtonFlow(MusicCommandService owner) {
        this.owner = owner;
    }

    public void run(ButtonInteractionEvent event) {
        owner.dispatchButtonFromGateway(event);
    }
}

