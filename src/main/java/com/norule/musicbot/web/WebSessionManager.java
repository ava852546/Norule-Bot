package com.norule.musicbot.web;

import com.norule.musicbot.config.BotConfig;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class WebSessionManager {
    private static final String SESSION_COOKIE = "norule_session";

    private final Map<String, OAuthState> oauthStates = new ConcurrentHashMap<>();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();

    Map<String, OAuthState> oauthStates() {
        return oauthStates;
    }

    Map<String, WebSession> sessions() {
        return sessions;
    }

    WebSession requireSession(HttpExchange exchange) {
        cleanupExpired();
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        String sessionId = null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && SESSION_COOKIE.equals(kv[0].trim())) {
                sessionId = kv[1].trim();
                break;
            }
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        WebSession session = sessions.get(sessionId);
        if (session == null || session.expiresAtMillis < System.currentTimeMillis()) {
            sessions.remove(sessionId);
            return null;
        }
        return session;
    }

    void setSessionCookie(HttpExchange exchange, String sessionId, BotConfig.Web web) {
        boolean secure = web.getSsl().isEnabled()
                || (web.getBaseUrl() != null && web.getBaseUrl().toLowerCase().startsWith("https://"));
        String sameSite = secure ? "None" : "Lax";
        int maxAge = Math.max(300, web.getSessionExpireMinutes() * 60);
        String cookie = SESSION_COOKIE + "=" + sessionId
                + "; Path=/; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=" + sameSite
                + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    void clearSessionCookie(HttpExchange exchange, BotConfig.Web web) {
        boolean secure = web.getSsl().isEnabled()
                || (web.getBaseUrl() != null && web.getBaseUrl().toLowerCase().startsWith("https://"));
        String sameSite = secure ? "None" : "Lax";
        String cookie = SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=" + sameSite + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    void cleanupExpired() {
        long now = System.currentTimeMillis();
        oauthStates.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        sessions.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
    }

    static class OAuthState {
        final long expiresAtMillis;

        OAuthState(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    static class WebSession {
        final String userId;
        final String username;
        final String avatarUrl;
        final String accessToken;
        final long expiresAtMillis;

        WebSession(String userId, String username, String avatarUrl, String accessToken, long expiresAtMillis) {
            this.userId = userId;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.accessToken = accessToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
