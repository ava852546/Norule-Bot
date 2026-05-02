package com.norule.musicbot.web.service;

import com.norule.musicbot.web.session.WebSessionManager;
import com.sun.net.httpserver.HttpExchange;

public final class WebSessionService {
    private final WebSessionManager sessionManager;

    public WebSessionService(WebSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void putOAuthState(String state, long expiresAtMillis) {
        sessionManager.oauthStates().put(state, new WebSessionManager.OAuthState(expiresAtMillis));
    }

    public WebSessionManager.OAuthState popOAuthState(String state) {
        return sessionManager.oauthStates().remove(state);
    }

    public WebSessionManager.WebSession requireSession(HttpExchange exchange) {
        return sessionManager.requireSession(exchange);
    }

    public void putSession(HttpExchange exchange,
                           String sessionId,
                           String userId,
                           String username,
                           String avatarUrl,
                           String accessToken,
                           long expiresAtMillis,
                           boolean secureCookie,
                           int sessionExpireMinutes) {
        sessionManager.sessions().put(sessionId, new WebSessionManager.WebSession(
                userId,
                username,
                avatarUrl,
                accessToken,
                expiresAtMillis
        ));
        sessionManager.setSessionCookie(exchange, sessionId, secureCookie, sessionExpireMinutes);
    }

    public void clearSessionCookie(HttpExchange exchange, boolean secureCookie) {
        sessionManager.clearSessionCookie(exchange, secureCookie);
    }
}
