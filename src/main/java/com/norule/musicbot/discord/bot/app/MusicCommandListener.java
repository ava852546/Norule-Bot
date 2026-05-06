package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.HoneypotService;
import com.norule.musicbot.ModerationService;
import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.TicketService;
import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.discord.bot.app.stats.MessageStatsEventService;
import com.norule.musicbot.discord.bot.gateway.InteractionGateway;
import com.norule.musicbot.discord.bot.ops.meta.DevOps;
import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import com.norule.musicbot.discord.bot.service.meta.DevService;
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
    private final InteractionGateway gateway;
    private final DevOps devOps;

    public MusicCommandListener(MusicPlayerService musicService,
                                RuntimeConfigSnapshot runtimeConfig,
                                GuildSettingsService settingsService,
                                ModerationService moderationService,
                                HoneypotService honeypotService,
                                ShortUrlService shortUrlService,
                                TicketService ticketService) {
        this(musicService, runtimeConfig, settingsService, moderationService, honeypotService, shortUrlService, ticketService, null);
    }

    public MusicCommandListener(MusicPlayerService musicService,
                                RuntimeConfigSnapshot runtimeConfig,
                                GuildSettingsService settingsService,
                                ModerationService moderationService,
                                HoneypotService honeypotService,
                                ShortUrlService shortUrlService,
                                TicketService ticketService,
                                MessageStatsEventService statsEventService) {
        this(musicService, runtimeConfig, settingsService, moderationService, honeypotService, new InMemorySignals(), shortUrlService, ticketService, statsEventService, null);
    }

    public MusicCommandListener(MusicPlayerService musicService,
                                RuntimeConfigSnapshot runtimeConfig,
                                GuildSettingsService settingsService,
                                ModerationService moderationService,
                                HoneypotService honeypotService,
                                Signals signals,
                                ShortUrlService shortUrlService,
                                TicketService ticketService) {
        this(musicService, runtimeConfig, settingsService, moderationService, honeypotService, signals, shortUrlService, ticketService, null, null);
    }

    public MusicCommandListener(MusicPlayerService musicService,
                                RuntimeConfigSnapshot runtimeConfig,
                                GuildSettingsService settingsService,
                                ModerationService moderationService,
                                HoneypotService honeypotService,
                                Signals signals,
                                ShortUrlService shortUrlService,
                                TicketService ticketService,
                                MessageStatsEventService statsEventService,
                                WordChainOps wordChainOps) {
        this.service = new MusicCommandService(
                musicService,
                runtimeConfig,
                settingsService,
                moderationService,
                honeypotService,
                shortUrlService,
                ticketService,
                statsEventService,
                wordChainOps
        );
        this.gateway = new InteractionGateway(service, signals);
        this.devOps = new DevOps(new DevService(runtimeConfig, musicService::getActivePlaybackGuildCount));
    }

    public void reloadRuntimeConfig(RuntimeConfigSnapshot newConfig) {
        service.reloadRuntimeConfig(newConfig);
        devOps.reloadRuntimeConfig(newConfig);
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
        if (devOps.handleMessage(event)) {
            return;
        }
        service.onMessageReceived(event);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        gateway.onSlash(event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        gateway.onAutoComplete(event);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        gateway.onStringSelect(event);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        gateway.onModal(event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (devOps.handleButton(event)) {
            return;
        }
        gateway.onButton(event);
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        gateway.onEntitySelect(event);
    }
}

