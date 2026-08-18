package com.norule.musicbot.web.service;

import com.norule.musicbot.web.session.WebSessionManager;
import com.sun.net.httpserver.HttpExchange;

import java.util.UUID;

public final class WebSessionService {
    private final WebSessionManager sessionManager;

    public WebSessionService(WebSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void putOAuthState(String state, long expiresAtMillis) {
        putOAuthState(state, expiresAtMillis, "");
    }

    public void putOAuthState(String state, long expiresAtMillis, String returnTo) {
        putOAuthState(state, expiresAtMillis, returnTo, "");
    }

    public void putOAuthState(String state,
                              long expiresAtMillis,
                              String returnTo,
                              String anonymousDeviceToken) {
        sessionManager.oauthStates().put(
                state, new WebSessionManager.OAuthState(expiresAtMillis, returnTo, anonymousDeviceToken));
    }

    public WebSessionManager.OAuthState popOAuthState(String state) {
        return sessionManager.oauthStates().remove(state);
    }

    public String issuePendingLogin(String returnTo, String anonymousDeviceToken, long expiresAtMillis) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        sessionManager.pendingLogins().put(requestId, new WebSessionManager.PendingLogin(
                returnTo, anonymousDeviceToken, expiresAtMillis));
        return requestId;
    }

    public WebSessionManager.PendingLogin consumePendingLogin(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        WebSessionManager.PendingLogin pending = sessionManager.pendingLogins().remove(requestId.trim());
        return pending == null || pending.expiresAtMillis < System.currentTimeMillis() ? null : pending;
    }

    public WebSessionManager.WebSession requireSession(HttpExchange exchange) {
        return sessionManager.requireSession(exchange);
    }

    public WebSessionManager.WebSession putSession(HttpExchange exchange,
                                                   String sessionId,
                                                   String userId,
                                                   String username,
                                                   String avatarUrl,
                                                   String accessToken,
                                                   long expiresAtMillis,
                                                   boolean secureCookie,
                                                   int sessionExpireMinutes) {
        WebSessionManager.WebSession session = new WebSessionManager.WebSession(
                userId,
                username,
                avatarUrl,
                accessToken,
                expiresAtMillis
        );
        sessionManager.sessions().put(sessionId, session);
        sessionManager.setSessionCookie(exchange, sessionId, secureCookie, sessionExpireMinutes);
        return session;
    }

    public String issueSessionHandoff(WebSessionManager.WebSession session, long expiresAtMillis) {
        if (session == null) {
            return "";
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        sessionManager.sessionHandoffs().put(
                ticket, new WebSessionManager.SessionHandoff(session, expiresAtMillis));
        return ticket;
    }

    public String activateSessionHandoff(HttpExchange exchange,
                                         String ticket,
                                         boolean secureCookie,
                                         int sessionExpireMinutes) {
        if (ticket == null || ticket.isBlank()) {
            return "";
        }
        WebSessionManager.SessionHandoff handoff = sessionManager.sessionHandoffs().remove(ticket.trim());
        long now = System.currentTimeMillis();
        if (handoff == null || handoff.expiresAtMillis < now
                || handoff.session == null || handoff.session.expiresAtMillis < now) {
            return "";
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        sessionManager.sessions().put(sessionId, handoff.session);
        sessionManager.setSessionCookie(exchange, sessionId, secureCookie, sessionExpireMinutes);
        return handoff.session.userId;
    }

    public void clearSessionCookie(HttpExchange exchange, boolean secureCookie) {
        sessionManager.clearSessionCookie(exchange, secureCookie);
    }
}
