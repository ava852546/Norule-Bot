package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class NotificationListener extends ListenerAdapter {
    private final NotificationService service;

    public NotificationListener(GuildSettingsService guildSettingsService, I18nService i18n) {
        this.service = new NotificationService(guildSettingsService, i18n);
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        service.onGuildMemberJoin(event);
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        service.onGuildMemberRemove(event);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        service.onGuildVoiceUpdate(event);
    }
}

