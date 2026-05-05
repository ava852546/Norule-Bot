package com.norule.musicbot.discord.bot.ops.meta;

import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.discord.bot.service.meta.DevService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public final class DevOps {
    private final DevService devService;

    public DevOps(DevService devService) {
        this.devService = devService;
    }

    public void reloadRuntimeConfig(RuntimeConfigSnapshot newConfig) {
        devService.reloadRuntimeConfig(newConfig);
    }

    public boolean handleMessage(MessageReceivedEvent event) {
        String raw = event.getMessage().getContentRaw();
        if (!devService.isDeveloperCommand(raw)) {
            return false;
        }
        devService.handleMessage(event, raw);
        return true;
    }

    public boolean handleButton(ButtonInteractionEvent event) {
        return devService.handleButton(event);
    }
}
