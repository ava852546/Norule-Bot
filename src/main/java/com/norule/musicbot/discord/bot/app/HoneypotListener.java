package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.HoneypotService;
import com.norule.musicbot.config.GuildSettingsService;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class HoneypotListener extends ListenerAdapter {
    public static final String CHANNEL_NAME = HoneypotEventService.CHANNEL_NAME;
    public static final String CHANNEL_TOPIC = HoneypotEventService.CHANNEL_TOPIC;
    private final HoneypotEventService service;

    public HoneypotListener(HoneypotService honeypotService,
                            GuildSettingsService settingsService) {
        this.service = new HoneypotEventService(honeypotService, settingsService);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        service.onMessageReceived(event);
    }

    public static net.dv8tion.jda.api.EmbedBuilder warningEmbed() {
        return HoneypotEventService.warningEmbed();
    }
}

