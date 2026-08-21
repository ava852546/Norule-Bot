package com.norule.musicbot.web.security;

import com.norule.musicbot.web.TestHttpExchange;
import com.norule.musicbot.web.session.WebSessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfProtectionServiceTest {
    private final CsrfProtectionService protection = new CsrfProtectionService();
    private final WebSessionManager.WebSession session = session("session-a-token");

    @Test
    void acceptsOnlyTheTokenBoundToTheCurrentSessionForMutations() {
        TestHttpExchange valid = mutation().header(CsrfProtectionService.HEADER_NAME, session.csrfToken);
        TestHttpExchange missing = mutation();
        TestHttpExchange invalid = mutation().header(CsrfProtectionService.HEADER_NAME, "wrong-token");
        TestHttpExchange otherSession = mutation().header(
                CsrfProtectionService.HEADER_NAME, session("session-b-token").csrfToken);

        assertTrue(protection.validate(valid, session));
        assertFalse(protection.validate(missing, session));
        assertFalse(protection.validate(invalid, session));
        assertFalse(protection.validate(otherSession, session));
    }

    @Test
    void doesNotRequireTokensForSafeMethods() {
        assertTrue(protection.validate(new TestHttpExchange("GET", "/api/guild/1/settings", null), session));
        assertTrue(protection.validate(new TestHttpExchange("HEAD", "/api/guild/1/settings", null), session));
        assertTrue(protection.validate(new TestHttpExchange("OPTIONS", "/api/guild/1/settings", null), session));
    }

    private TestHttpExchange mutation() {
        return new TestHttpExchange("POST", "/api/guild/1/number-chain/reset", null);
    }

    private WebSessionManager.WebSession session(String csrfToken) {
        return new WebSessionManager.WebSession(
                "1", "user", "", "access-token", csrfToken, System.currentTimeMillis() + 60_000L);
    }
}
