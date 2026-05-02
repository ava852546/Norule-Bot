package com.norule.musicbot.discord.bot.app.stats;

import com.norule.musicbot.domain.stats.MessageStatsService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MessageStatsListener extends ListenerAdapter {
    private final MessageStatsEventService service;

    public MessageStatsListener(MessageStatsService statsService) {
        this.service = new MessageStatsEventService(statsService);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        service.onMessageReceived(event);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        service.onGuildVoiceUpdate(event);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        service.onSlashCommandInteraction(event);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        service.onStringSelectInteraction(event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        service.onButtonInteraction(event);
    }
}

