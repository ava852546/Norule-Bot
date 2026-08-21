package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.security.HttpRequestBodyReader;
import com.norule.musicbot.web.service.GuildSettingsWebService;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;

public final class GuildSettingsController {
    private final WebControlServer owner;
    private final GuildSettingsWebService guildSettingsWebService;

    public GuildSettingsController(WebControlServer owner) {
        this.owner = owner;
        this.guildSettingsWebService = new GuildSettingsWebService(owner);
    }

    public void handleApiGuildRoute(HttpExchange exchange) throws IOException {
        try {
            guildSettingsWebService.handleApiGuildRoute(exchange);
        } catch (HttpRequestBodyReader.RequestBodyTooLargeException ignored) {
            owner.sendJson(exchange, 413, DataObject.empty()
                    .put("error", "Request body too large")
                    .put("errorCode", "REQUEST_BODY_TOO_LARGE"));
        }
    }
}
