package com.norule.musicbot.web.service;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import com.norule.musicbot.web.TestHttpExchange;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.infra.WebSettings;
import com.norule.musicbot.web.session.WebSessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSecurityIntegrationTest {
    @Test
    void pendingLoginRequiresPostBodyProofAndCallbackRequiresBrowserCookie() throws Exception {
        WebControlServer owner = newOwner();
        try {
            WebSessionService sessions = new WebSessionService(owner.sessionManager());
            WebAuthService auth = new WebAuthService(owner, sessions);
            String request = sessions.issuePendingLogin("/dashboard", "source-device", System.currentTimeMillis() + 60_000);
            TestHttpExchange stolenLink = new TestHttpExchange("GET", "/auth/login?request=" + request, null);
            auth.handleAuthLogin(stolenLink);
            assertEquals(401, stolenLink.responseCode());
            TestHttpExchange wrongProof = new TestHttpExchange("POST", "/auth/login?request=" + request,
                    "browserProof=attacker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            auth.handleAuthLogin(wrongProof);
            assertEquals(401, wrongProof.responseCode());
            TestHttpExchange login = new TestHttpExchange("POST", "/auth/login?request=" + request,
                    "browserProof=source-device".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            auth.handleAuthLogin(login);
            assertTrue(login.responseCode() >= 300 && login.responseCode() < 400);
            assertTrue(login.getResponseHeaders().getFirst("Set-Cookie").contains("HttpOnly"));
            String state = owner.parseUrlEncoded(java.net.URI.create(login.getResponseHeaders().getFirst("Location")).getRawQuery()).get("state");
            TestHttpExchange stolenCallback = new TestHttpExchange("GET", "/auth/callback?state=" + state + "&code=attacker", null);
            auth.handleAuthCallback(stolenCallback);
            assertEquals(401, stolenCallback.responseCode());
            assertTrue(owner.sessionManager().sessions().isEmpty());
            TestHttpExchange replay = new TestHttpExchange("POST", "/auth/login?request=" + request,
                    "browserProof=source-device".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            auth.handleAuthLogin(replay);
            assertEquals(401, replay.responseCode());
            TestHttpExchange directLogin = new TestHttpExchange("GET", "/auth/login", null);
            auth.handleAuthLogin(directLogin);
            assertTrue(directLogin.responseCode() >= 300 && directLogin.responseCode() < 400);
            owner.shortUrlService().updateOptions(new ShortUrlService.Options(
                    true, 60_000, 60_000, "https://short.example.com", 7, false));
            TestHttpExchange crossOrigin = new TestHttpExchange("GET",
                    "/auth/login?returnTo=https%3A%2F%2Fshort.example.com%2Fmy", null);
            auth.handleAuthLogin(crossOrigin);
            java.net.URI destination = java.net.URI.create(crossOrigin.getResponseHeaders().getFirst("Location"));
            assertEquals("short.example.com", destination.getHost());
            assertEquals("/api/short/session/login", destination.getPath());
            assertEquals("https://short.example.com/my", owner.parseUrlEncoded(destination.getRawQuery()).get("returnTo"));
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void numberChainResetWithoutCsrfStopsBeforeMutation() throws Exception {
        WebControlServer owner = newOwner();
        try {
            putSession(owner, "session-a", "csrf-a");
            TestHttpExchange exchange = new TestHttpExchange(
                    "POST", "/api/guild/123/number-chain/reset", null)
                    .header("Cookie", "norule_session=session-a");

            new GuildSettingsWebService(owner).handleApiGuildRoute(exchange);

            assertEquals(403, exchange.responseCode());
            assertTrue(exchange.responseBodyUtf8().contains("INVALID_CSRF_TOKEN"));
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void apiMeReturnsTheSessionTokenWithoutCaching() throws Exception {
        WebControlServer owner = newOwner();
        try {
            putSession(owner, "session-a", "csrf-a");
            TestHttpExchange exchange = new TestHttpExchange("GET", "/api/me", null)
                    .header("Cookie", "norule_session=session-a");

            new WebAuthService(owner, new WebSessionService(owner.sessionManager())).handleApiMe(exchange);

            assertEquals(200, exchange.responseCode());
            assertEquals("private, no-store", exchange.getResponseHeaders().getFirst("Cache-Control"));
            assertTrue(exchange.responseBodyUtf8().contains("\"csrfToken\":\"csrf-a\""));
        } finally {
            owner.shutdown();
        }
    }

    private WebControlServer newOwner() {
        return new WebControlServer(
                null,
                null,
                null,
                null,
                null,
                new ShortUrlService(new EmptyShortUrlRepository()),
                () -> new WebSettings(false, 60_000, "https://dash.example.com", 60, "", "", ""),
                null,
                () -> "lang",
                null
        );
    }

    private void putSession(WebControlServer owner, String sessionId, String csrfToken) {
        owner.sessionManager().sessions().put(sessionId, new WebSessionManager.WebSession(
                "123", "user", "", "access-token", csrfToken,
                System.currentTimeMillis() + 60_000L));
    }

    private static final class EmptyShortUrlRepository implements ShortUrlRepository {
        @Override
        public ShortUrlService.ShortUrlEntry findByCode(String code) { return null; }

        @Override
        public ShortUrlService.ShortUrlEntry findActiveByTarget(String target, long nowMillis) { return null; }

        @Override
        public void save(ShortUrlService.ShortUrlEntry entry) { }

        @Override
        public void deleteByCode(String code) { }

        @Override
        public int cleanupExpired(long nowMillis) { return 0; }

        @Override
        public long incrementViewCount(String code) { return 0L; }

        @Override
        public Long findLogChannelId() { return null; }

        @Override
        public void saveLogChannelId(Long channelId) { }
    }
}
