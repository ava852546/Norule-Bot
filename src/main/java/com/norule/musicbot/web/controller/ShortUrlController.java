package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.service.ShortUrlWebService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public final class ShortUrlController {
    private final ShortUrlWebService shortUrlWebService;

    public ShortUrlController(WebControlServer owner) {
        this.shortUrlWebService = new ShortUrlWebService(owner);
    }

    public void handleCreateShortUrl(HttpExchange exchange) throws IOException {
        shortUrlWebService.handleCreateShortUrl(exchange);
    }

    public void handleResolveShortUrl(HttpExchange exchange) throws IOException {
        shortUrlWebService.handleResolveShortUrl(exchange);
    }
}
