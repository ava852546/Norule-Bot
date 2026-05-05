package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.ModerationService;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class DuplicateMessageListener extends ListenerAdapter {
    private final DuplicateMessageService service;

    public DuplicateMessageListener(GuildSettingsService settingsService,
                                    ModerationService moderationService,
                                    I18nService i18n,
                                    DuplicateMessageCacheRepository cacheRepository) {
        this.service = new DuplicateMessageService(settingsService, moderationService, i18n, cacheRepository);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        service.onMessageReceived(event);
    }
}

