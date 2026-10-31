package com.norule.musicbot.discord.bot.gateway.listener;


import com.norule.musicbot.discord.bot.gateway.DiscordRateLimitInterceptor;

import com.norule.musicbot.discord.bot.gateway.DiscordBackgroundRestQueue;
import com.norule.musicbot.discord.bot.service.cache.*;
import com.norule.musicbot.discord.bot.service.privateroom.PrivateRoomService;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class PrivateRoomListener extends ListenerAdapter {
    private final PrivateRoomService service;
    private final DiscordBackgroundRestQueue background;

    public PrivateRoomListener(GuildSettingsService settingsService, I18nService i18n, PrivateRoomCacheRepository cacheRepository) {
        this(settingsService, i18n, cacheRepository, null);
    }

    public PrivateRoomListener(GuildSettingsService settingsService, I18nService i18n,
            PrivateRoomCacheRepository cacheRepository,
            DiscordBackgroundRestQueue background) {
        this.service = new PrivateRoomService(settingsService, i18n, cacheRepository);
        this.background = background;
    }

    @Override
    public void onReady(ReadyEvent event) {
        if (background == null) {
            service.onReady(event);
        } else {
            service.onReady(event, (id, action) -> background.submit("private-room:" + id,
                    () -> DiscordRateLimitInterceptor.track(
                            action, "PRIVATE_ROOM_STARTUP_CLEANUP", true, true,
                            "DELETE", "/channels/" + id).submit()));
        }
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        service.onGuildVoiceUpdate(event);
    }

    public static boolean isManagedPrivateRoom(long guildId, long channelId) {
        return PrivateRoomService.isManagedPrivateRoom(guildId, channelId);
    }

    public static boolean isRoomOwner(long guildId, long channelId, long userId) {
        return PrivateRoomService.isRoomOwner(guildId, channelId, userId);
    }

    public static Long getRoomOwnerId(long guildId, long channelId) {
        return PrivateRoomService.getRoomOwnerId(guildId, channelId);
    }

    public static void setRoomOwner(long guildId, long channelId, long userId) {
        PrivateRoomService.setRoomOwner(guildId, channelId, userId);
    }

    public static long getRoomOwnerPermissionRaw() {
        return PrivateRoomService.getRoomOwnerPermissionRaw();
    }
}

