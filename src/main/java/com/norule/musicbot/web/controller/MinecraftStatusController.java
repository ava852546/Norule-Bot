package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.service.MinecraftStatusWebService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public final class MinecraftStatusController {
    private final MinecraftStatusWebService minecraftStatusWebService;

    public MinecraftStatusController(MinecraftStatusWebService minecraftStatusWebService) {
        this.minecraftStatusWebService = minecraftStatusWebService;
    }

    public void handleApiMinecraftStatus(HttpExchange exchange) throws IOException {
        minecraftStatusWebService.handleApiMinecraftStatus(exchange);
    }
}
