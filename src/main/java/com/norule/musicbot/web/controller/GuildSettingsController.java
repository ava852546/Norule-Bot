package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.service.GuildSettingsWebService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public final class GuildSettingsController {
    private final GuildSettingsWebService guildSettingsWebService;

    public GuildSettingsController(WebControlServer owner) {
        this.guildSettingsWebService = new GuildSettingsWebService(owner);
    }

    public void handleApiGuildRoute(HttpExchange exchange) throws IOException {
        guildSettingsWebService.handleApiGuildRoute(exchange);
    }
}
