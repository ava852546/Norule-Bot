package com.norule.musicbot.discord.bot.gateway.listener;

import com.norule.musicbot.discord.bot.service.stats.MessageStatsEventService;

import com.norule.musicbot.domain.stats.MessageStatsService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MessageStatsListener extends ListenerAdapter {
    private final MessageStatsEventService service;

    public MessageStatsListener(MessageStatsService statsService) {
        this.service = new MessageStatsEventService(statsService);
    }

    public MessageStatsEventService service() {
        return service;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        service.onMessageReceived(event);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        service.onGuildVoiceUpdate(event);
    }
}

