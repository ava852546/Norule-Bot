package com.norule.musicbot.web.service;

import com.norule.musicbot.web.TestHttpExchange;
import com.norule.musicbot.web.session.WebSessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        String handoff = service.issueSessionHandoff(first, expiresAt, "source-device");
        assertEquals("", service.activateSessionHandoff(
                new TestHttpExchange("GET", "/", null), handoff, true, 60));
        assertEquals("", service.activateSessionHandoff(
                new TestHttpExchange("GET", "/", null).header("Cookie", "nr_anon_device=attacker"), handoff, true, 60));
        service.activateSessionHandoff(
                new TestHttpExchange("GET", "/?__nr_auth=" + handoff, null)
                        .header("Cookie", "nr_anon_device=source-device"), handoff, true, 60);
        assertEquals("", service.activateSessionHandoff(
                new TestHttpExchange("GET", "/", null).header("Cookie", "nr_anon_device=source-device"), handoff, true, 60));

        assertSame(first, manager.sessions().values().stream()
                .filter(session -> session.userId.equals("1"))
                .reduce((previous, current) -> current)
                .orElseThrow());
        assertEquals(first.csrfToken, manager.sessions().values().stream()
                .filter(session -> session.userId.equals("1"))
                .findFirst()
                .orElseThrow().csrfToken);
    }

    @Test
    void oauthStateRequiresInitiatingBrowserAndIsSingleUse() {
        WebSessionService service = new WebSessionService(new WebSessionManager());
        TestHttpExchange login = new TestHttpExchange("GET", "/auth/login", null);
        service.putOAuthState(login, "state", System.currentTimeMillis() + 60_000, "/", "device", true);
        String browserCookie = login.getResponseHeaders().getFirst("Set-Cookie").split(";", 2)[0];
        assertNull(service.popOAuthState(new TestHttpExchange("GET", "/auth/callback", null), "state"));
        assertNull(service.popOAuthState(new TestHttpExchange("GET", "/auth/callback", null)
                .header("Cookie", "nr_oauth_browser=attacker"), "state"));
        TestHttpExchange callback = new TestHttpExchange("GET", "/auth/callback", null).header("Cookie", browserCookie);
        assertEquals("device", service.popOAuthState(callback, "state").anonymousDeviceToken);
        assertNull(service.popOAuthState(callback, "state"));
    }

    @Test
    void logoutRevokesSessionAliasesAndOutstandingHandoffsButKeepsIndependentLogin() {
        WebSessionManager manager = new WebSessionManager();
        WebSessionService service = new WebSessionService(manager);
        long expiry = System.currentTimeMillis() + 60_000;
        assertEquals("", service.issueSessionHandoff(null, expiry, "device"));
        var session = service.putSession(new TestHttpExchange("GET", "/", null),
                "original", "1", "user", "", "token", expiry, true, 60);
        service.putSession(new TestHttpExchange("GET", "/", null),
                "independent", "1", "user", "", "token2", expiry, true, 60);
        String outstanding = service.issueSessionHandoff(session, expiry, "device");
        String transfer = service.issueSessionHandoff(session, expiry, "device");
        service.activateSessionHandoff(new TestHttpExchange("GET", "/", null)
                .header("Cookie", "nr_anon_device=device"), transfer, true, 60);
        TestHttpExchange logout = new TestHttpExchange("POST", "/logout", null)
                .header("Cookie", "norule_session=original");
        service.clearSessionCookie(logout, true);
        assertNull(service.requireSession(logout));
        assertEquals(1, manager.sessions().size());
        assertTrue(manager.sessions().containsKey("independent"));
        assertEquals("", service.activateSessionHandoff(new TestHttpExchange("GET", "/", null)
                .header("Cookie", "nr_anon_device=device"), outstanding, true, 60));
        assertTrue(logout.getResponseHeaders().getFirst("Set-Cookie").contains("Max-Age=0"));
    }
}
