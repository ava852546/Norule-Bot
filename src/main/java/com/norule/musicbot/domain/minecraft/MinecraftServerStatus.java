package com.norule.musicbot.domain.minecraft;

public record MinecraftServerStatus(
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
    public MinecraftServerStatus withCached(boolean cached) {
        return new MinecraftServerStatus(
                online,
                address,
                ip,
                port,
                version,
                playersOnline,
                playersMax,
                motd,
                iconUrl,
                serverType,
                cached
        );
    }
}
