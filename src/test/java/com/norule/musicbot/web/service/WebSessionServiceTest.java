package com.norule.musicbot.web.service;

import com.norule.musicbot.web.TestHttpExchange;
import com.norule.musicbot.web.session.WebSessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSessionServiceTest {
    @Test
    void createsIndependentSecureTokensAndPreservesThemAcrossHandoff() {
        WebSessionManager manager = new WebSessionManager();
        WebSessionService service = new WebSessionService(manager);
        TestHttpExchange firstResponse = new TestHttpExchange("GET", "/auth/callback", null);
        TestHttpExchange secondResponse = new TestHttpExchange("GET", "/auth/callback", null);
        long expiresAt = System.currentTimeMillis() + 60_000L;

        WebSessionManager.WebSession first = service.putSession(
                firstResponse, "session-a", "1", "first", "", "token-a", expiresAt, true, 60);
        WebSessionManager.WebSession second = service.putSession(
                secondResponse, "session-b", "2", "second", "", "token-b", expiresAt, true, 60);

        assertTrue(first.csrfToken.length() >= 43);
        assertNotEquals(first.csrfToken, second.csrfToken);
        assertTrue(firstResponse.getResponseHeaders().getFirst("Set-Cookie").contains("SameSite=Lax"));
        assertTrue(firstResponse.getResponseHeaders().getFirst("Set-Cookie").contains("Secure"));

        String handoff = service.issueSessionHandoff(first, expiresAt);
        service.activateSessionHandoff(
                new TestHttpExchange("GET", "/?__nr_auth=" + handoff, null), handoff, true, 60);

        assertSame(first, manager.sessions().values().stream()
                .filter(session -> session.userId.equals("1"))
                .reduce((previous, current) -> current)
                .orElseThrow());
        assertEquals(first.csrfToken, manager.sessions().values().stream()
                .filter(session -> session.userId.equals("1"))
                .findFirst()
                .orElseThrow().csrfToken);
    }
}
