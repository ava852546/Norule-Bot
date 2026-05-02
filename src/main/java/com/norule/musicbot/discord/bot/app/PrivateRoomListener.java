package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.nio.file.Path;

public class PrivateRoomListener extends ListenerAdapter {
    private final PrivateRoomService service;

    public PrivateRoomListener(GuildSettingsService settingsService, I18nService i18n, Path cacheDir) {
        this.service = new PrivateRoomService(settingsService, i18n, cacheDir);
    }

    @Override
    public void onReady(ReadyEvent event) {
        service.onReady(event);
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

