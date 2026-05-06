package com.norule.musicbot.web.dto;

import com.norule.musicbot.domain.minecraft.MinecraftServerType;

public record MinecraftStatusWebResponse(
        boolean online,
        String address,
        String ip,
        int port,
        String version,
        int playersOnline,
        int playersMax,
        String motd,
        String iconUrl,
        MinecraftServerType serverType,
        boolean cached
) {
}
