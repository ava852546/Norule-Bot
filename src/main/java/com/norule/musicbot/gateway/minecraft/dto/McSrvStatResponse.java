package com.norule.musicbot.gateway.minecraft.dto;

import java.util.List;

public record McSrvStatResponse(
        boolean online,
        String ip,
        int port,
        String version,
        int playersOnline,
        int playersMax,
        List<String> motdLines,
        boolean cacheHit
) {
}
