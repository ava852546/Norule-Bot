package com.norule.musicbot.web.session;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSessionManager {
    private static final String SESSION_COOKIE = "norule_session";

    private final Map<String, OAuthState> oauthStates = new ConcurrentHashMap<>();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();

    public Map<String, OAuthState> oauthStates() {
        return oauthStates;
    }

    public Map<String, WebSession> sessions() {
        return sessions;
    }

    public WebSession requireSession(HttpExchange exchange) {
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

    public void setSessionCookie(HttpExchange exchange, String sessionId, boolean secureCookie, int sessionExpireMinutes) {
        String sameSite = secureCookie ? "None" : "Lax";
        int maxAge = Math.max(300, Math.max(5, sessionExpireMinutes) * 60);
        String cookie = SESSION_COOKIE + "=" + sessionId
                + "; Path=/; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=" + sameSite
                + (secureCookie ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    public void clearSessionCookie(HttpExchange exchange, boolean secureCookie) {
        String sameSite = secureCookie ? "None" : "Lax";
        String cookie = SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=" + sameSite
                + (secureCookie ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        oauthStates.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        sessions.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
    }

    public static class OAuthState {
        public final long expiresAtMillis;

        public OAuthState(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    public static class WebSession {
        public final String userId;
        public final String username;
        public final String avatarUrl;
        public final String accessToken;
        public final long expiresAtMillis;

        public WebSession(String userId, String username, String avatarUrl, String accessToken, long expiresAtMillis) {
            this.userId = userId;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.accessToken = accessToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
