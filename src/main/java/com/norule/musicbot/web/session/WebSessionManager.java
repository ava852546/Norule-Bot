package com.norule.musicbot.web.session;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSessionManager {
    private static final String SESSION_COOKIE = "norule_session";

    private final Map<String, OAuthState> oauthStates = new ConcurrentHashMap<>();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionHandoff> sessionHandoffs = new ConcurrentHashMap<>();
    private final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();

    public Map<String, OAuthState> oauthStates() {
        return oauthStates;
    }

    public Map<String, WebSession> sessions() {
        return sessions;
    }

    public Map<String, SessionHandoff> sessionHandoffs() {
        return sessionHandoffs;
    }

    public Map<String, PendingLogin> pendingLogins() {
        return pendingLogins;
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
        int maxAge = Math.max(300, Math.max(5, sessionExpireMinutes) * 60);
        String cookie = SESSION_COOKIE + "=" + sessionId
                + "; Path=/; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=Lax"
                + (secureCookie ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    public void clearSessionCookie(HttpExchange exchange, boolean secureCookie) {
        String cookie = SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                + (secureCookie ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        oauthStates.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        sessions.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        sessionHandoffs.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        pendingLogins.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
    }

    public static class OAuthState {
        public final long expiresAtMillis;
        public final String returnTo;
        public final String anonymousDeviceToken;

        public OAuthState(long expiresAtMillis) {
            this(expiresAtMillis, "", "");
        }

        public OAuthState(long expiresAtMillis, String returnTo) {
            this(expiresAtMillis, returnTo, "");
        }

        public OAuthState(long expiresAtMillis, String returnTo, String anonymousDeviceToken) {
            this.expiresAtMillis = expiresAtMillis;
            this.returnTo = returnTo == null ? "" : returnTo;
            this.anonymousDeviceToken = anonymousDeviceToken == null ? "" : anonymousDeviceToken;
        }
    }

    public static class PendingLogin {
        public final String returnTo;
        public final String anonymousDeviceToken;
        public final long expiresAtMillis;

        public PendingLogin(String returnTo, String anonymousDeviceToken, long expiresAtMillis) {
            this.returnTo = returnTo == null ? "" : returnTo;
            this.anonymousDeviceToken = anonymousDeviceToken == null ? "" : anonymousDeviceToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    public static class SessionHandoff {
        public final WebSession session;
        public final long expiresAtMillis;

        public SessionHandoff(WebSession session, long expiresAtMillis) {
            this.session = session;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    public static class WebSession {
        public final String userId;
        public final String username;
        public final String avatarUrl;
        public final String accessToken;
        public final String csrfToken;
        public final long expiresAtMillis;

        public WebSession(String userId,
                          String username,
                          String avatarUrl,
                          String accessToken,
                          String csrfToken,
                          long expiresAtMillis) {
            this.userId = userId;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.accessToken = accessToken;
            this.csrfToken = csrfToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
