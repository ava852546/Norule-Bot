package com.norule.musicbot.gateway.minecraft;

import com.norule.musicbot.config.domain.MinecraftStatusConfig;
import com.norule.musicbot.domain.minecraft.MinecraftServerType;
import com.norule.musicbot.gateway.minecraft.dto.McSrvStatResponse;

import java.io.IOException;

public interface MinecraftStatusGateway {
    McSrvStatResponse fetchStatus(String address,
                                  MinecraftServerType serverType,
                                  MinecraftStatusConfig config) throws IOException, InterruptedException;

    String buildIconUrl(String address);
}
