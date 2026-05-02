package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.service.WebAuthService;
import com.norule.musicbot.web.service.WebSessionService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public final class WebAuthController {
    private final WebAuthService authService;

    public WebAuthController(WebControlServer owner) {
        this.authService = new WebAuthService(owner, new WebSessionService(owner.sessionManager()));
    }

    public void handleAuthLogin(HttpExchange exchange) throws IOException {
        authService.handleAuthLogin(exchange);
    }

    public void handleAuthCallback(HttpExchange exchange) throws IOException {
        authService.handleAuthCallback(exchange);
    }

    public void handleAuthLogout(HttpExchange exchange) throws IOException {
        authService.handleAuthLogout(exchange);
    }

    public void handleApiMe(HttpExchange exchange) throws IOException {
        authService.handleApiMe(exchange);
    }
}
