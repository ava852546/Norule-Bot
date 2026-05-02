package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class VoiceAutoLeaveListener extends ListenerAdapter {
    private final VoiceAutoLeaveService service;

    public VoiceAutoLeaveListener(GuildSettingsService settingsService, MusicPlayerService playerService, I18nService i18n) {
        this.service = new VoiceAutoLeaveService(settingsService, playerService, i18n);
    }

    @Override
    public void onReady(ReadyEvent event) {
        service.onReady(event);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        service.onGuildVoiceUpdate(event);
    }
}

