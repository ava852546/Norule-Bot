package com.norule.musicbot.discord.bot.gateway.listener;

import com.norule.musicbot.domain.monitoring.VoiceAutoLeaveEvent;

import com.norule.musicbot.discord.bot.service.voice.VoiceAutoLeaveService;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import java.util.function.Supplier;
import java.util.function.Consumer;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class VoiceAutoLeaveListener extends ListenerAdapter {
    private final VoiceAutoLeaveService service;

    public VoiceAutoLeaveListener(GuildSettingsService settingsService, MusicPlayerService playerService, Supplier<I18nService> i18n) {
        this(settingsService, playerService, i18n, ignored -> {});
    }

    public VoiceAutoLeaveListener(GuildSettingsService settingsService, MusicPlayerService playerService,
            Supplier<I18nService> i18n, Consumer<VoiceAutoLeaveEvent> events) {
        this.service = new VoiceAutoLeaveService(id -> MusicConfig.fromLegacy(settingsService.getMusic(id), null),
                settingsService::getLanguage, playerService, i18n, events);
    }

    @Override
    public void onReady(ReadyEvent event) {
        service.onConnected(event.getJDA());
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        service.onVoiceUpdate(event.getGuild(), event.getMember().equals(event.getGuild().getSelfMember()));
    }
    @Override
    public void onSessionResume(SessionResumeEvent event) {
        service.onConnected(event.getJDA());
    }

    @Override
    public void onSessionRecreate(SessionRecreateEvent event) {
        service.onConnected(event.getJDA());
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        service.close();
    }
}
