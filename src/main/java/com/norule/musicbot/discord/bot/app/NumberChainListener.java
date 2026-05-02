package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.ModerationService;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.function.LongSupplier;

public class NumberChainListener extends ListenerAdapter {
    private final NumberChainService service;

    public NumberChainListener(GuildSettingsService settingsService,
                               ModerationService moderationService,
                               I18nService i18n,
                               LongSupplier reactionDelayMillisSupplier) {
        this.service = new NumberChainService(settingsService, moderationService, i18n, reactionDelayMillisSupplier);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        service.onMessageReceived(event);
    }
}

