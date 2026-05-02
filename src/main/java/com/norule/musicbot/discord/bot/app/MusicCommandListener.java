package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.HoneypotService;
import com.norule.musicbot.ModerationService;
import com.norule.musicbot.TicketService;
import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.discord.gateway.InMemorySignals;
import com.norule.musicbot.discord.gateway.Signals;
import com.norule.musicbot.domain.music.MusicPlayerService;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MusicCommandListener extends ListenerAdapter {
    private final MusicCommandService service;

    public MusicCommandListener(MusicPlayerService musicService,
                                RuntimeConfigSnapshot runtimeConfig,
                                GuildSettingsService settingsService,
                                ModerationService moderationService,
                                HoneypotService honeypotService,
                                TicketService ticketService) {
        this(musicService, runtimeConfig, settingsService, moderationService, honeypotService, new InMemorySignals(), ticketService);
    }

    public MusicCommandListener(MusicPlayerService musicService,
                                RuntimeConfigSnapshot runtimeConfig,
                                GuildSettingsService settingsService,
                                ModerationService moderationService,
                                HoneypotService honeypotService,
                                Signals signals,
                                TicketService ticketService) {
        this.service = new MusicCommandService(musicService, runtimeConfig, settingsService, moderationService, honeypotService, ticketService);
    }

    public void reloadRuntimeConfig(RuntimeConfigSnapshot newConfig) {
        service.reloadRuntimeConfig(newConfig);
    }

    @Override
    public void onReady(ReadyEvent event) {
        service.onReady(event);
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        service.onGuildJoin(event);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        service.onMessageReceived(event);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        service.onSlashCommandInteraction(event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        service.onCommandAutoCompleteInteraction(event);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        service.onStringSelectInteraction(event);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        service.onModalInteraction(event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        service.onButtonInteraction(event);
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        service.onEntitySelectInteraction(event);
    }
}

