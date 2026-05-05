package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MessageLogListener extends ListenerAdapter {
    private final MessageLogService service;

    public MessageLogListener(GuildSettingsService settingsService, I18nService i18n, MessageLogCacheRepository cacheRepository) {
        this.service = new MessageLogService(settingsService, i18n, cacheRepository);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        service.onMessageReceived(event);
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        service.onMessageUpdate(event);
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        service.onMessageDelete(event);
    }
}

