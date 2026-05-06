package com.norule.musicbot.ops.minecraft;

import com.norule.musicbot.service.minecraft.MinecraftStatusService;

public final class MinecraftStatusOps {
    private final MinecraftStatusService minecraftStatusService;

    public MinecraftStatusOps(MinecraftStatusService minecraftStatusService) {
        if (minecraftStatusService == null) {
            throw new IllegalArgumentException("minecraftStatusService cannot be null");
        }
        this.minecraftStatusService = minecraftStatusService;
    }

    public MinecraftStatusService.QueryResult query(String address, String type) {
        return minecraftStatusService.query(address, type);
    }
}
