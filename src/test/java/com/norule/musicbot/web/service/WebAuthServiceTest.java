package com.norule.musicbot.web.service;

import com.norule.musicbot.web.session.WebSessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebAuthServiceTest {
    private static final String DASHBOARD = "https://dash.norule.me";
    private static final String SHORT_URL = "https://s.norule.me";

    @Test
    void allowsOnlyTheConfiguredShortUrlStatsDestination() {
        assertEquals("https://s.norule.me/abc123?stats", WebAuthService.sanitizeReturnTo(
                "https://s.norule.me/abc123?stats", DASHBOARD, SHORT_URL));
        assertEquals(DASHBOARD, WebAuthService.sanitizeReturnTo(
                "https://s.norule.me/abc123?", DASHBOARD, SHORT_URL));
        assertEquals(DASHBOARD, WebAuthService.sanitizeReturnTo(
                "https://s.norule.me/abc123?stats&next=https://evil.example", DASHBOARD, SHORT_URL));
        assertEquals(DASHBOARD, WebAuthService.sanitizeReturnTo(
                "https://evil.example/abc123?stats", DASHBOARD, SHORT_URL));
    }

    @Test
    void acceptsSafeDashboardRelativeDestinations() {
        assertEquals("/dashboard", WebAuthService.sanitizeReturnTo(
                "/dashboard", DASHBOARD, SHORT_URL));
        assertEquals(DASHBOARD, WebAuthService.sanitizeReturnTo(
                "//evil.example/dashboard", DASHBOARD, SHORT_URL));
    }

    @Test
    void pendingCrossOriginLoginContextIsOpaqueAndSingleUse() {
        WebSessionService sessions = new WebSessionService(new WebSessionManager());
        String requestId = sessions.issuePendingLogin(
                "https://s.norule.me/abc123?stats", "anonymous-device-token",
                System.currentTimeMillis() + 60_000L);

        WebSessionManager.PendingLogin pending = sessions.consumePendingLogin(requestId);

        assertNotNull(pending);
        assertEquals("https://s.norule.me/abc123?stats", pending.returnTo);
        assertEquals("anonymous-device-token", pending.anonymousDeviceToken);
        assertNull(sessions.consumePendingLogin(requestId));
    }
}
