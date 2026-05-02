package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.gateway.LegacyContract;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public final class ButtonFlow {
    private final MusicCommandService owner;

    public ButtonFlow(MusicCommandService owner) {
        this.owner = owner;
    }

    public void run(ButtonInteractionEvent event) {
        if (event.getComponentId() != null && !LegacyContract.isKnownInteractivePrefix(event.getComponentId())
                && event.getGuild() == null) {
            return;
        }
        owner.dispatchButtonFromGateway(event);
    }
}

