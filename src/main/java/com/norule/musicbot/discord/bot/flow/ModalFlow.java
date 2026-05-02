package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;

public final class ModalFlow {
    private final MusicCommandService owner;

    public ModalFlow(MusicCommandService owner) {
        this.owner = owner;
    }

    public void run(ModalInteractionEvent event) {
        owner.dispatchModalFromGateway(event);
    }
}

