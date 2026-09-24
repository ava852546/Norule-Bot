package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.shorturl.SqliteShortUrlRepository;
import com.norule.musicbot.web.session.WebSessionManager;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShortUrlTierPolicyTest {
    @TempDir Path temp;
    private ShortUrlService service;
    private com.norule.musicbot.service.shorturl.ImageShareService media;
    private ShortUrlGatewayServer gateway;
    private SqliteShortUrlRepository repository;
    private WebSessionManager sessions;
    private String base;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port;
        repository = new SqliteShortUrlRepository(temp.resolve("tiers.db"));
        media = new com.norule.musicbot.service.shorturl.ImageShareService(
                new com.norule.musicbot.shorturl.SqliteImageShareRepository(temp.resolve("tiers.db")),
                new com.norule.musicbot.shorturl.SqliteMediaBlobRepository(temp.resolve("tiers.db")), repository,
                new FileSystemImageShareStorage(temp.resolve("media")), null, null, null);
        service = new ShortUrlService(repository, null, media);
        sessions = new WebSessionManager();
        session("a", System.currentTimeMillis() + 60_000);
        session("b", System.currentTimeMillis() + 60_000);
        session("expired", 1);
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true, "bindPort", port, "publicBaseUrl", base), BotConfig.ShortUrl.defaultValues());
        gateway = new ShortUrlGatewayServer(service, () -> config, exchange -> {
            var session = sessions.requireSession(exchange);
            return session == null ? "" : session.userId;
        });
        gateway.syncWithConfig();
    }

    @AfterEach void stop() { gateway.shutdown(); }

    @Test
    void anonymousCreationIgnoresClientOwnerAndRedirectRemainsPublic() throws Exception {
        var created = request("POST", "/api/short", "", "{\"url\":\"https://example.com/a\",\"ownerUserId\":\"b\"}");
        assertEquals(200, created.statusCode());
        String code = DataObject.fromJson(created.body()).getString("code");
        assertEquals("", repository.findByCode(code).ownerUserId());
        assertEquals(Long.MAX_VALUE, repository.findByCode(code).expiresAt());
        var redirect = request("GET", "/" + code, "", "");
        assertEquals(302, redirect.statusCode());
        assertEquals("https://example.com/a", redirect.headers().firstValue("Location").orElseThrow());
        assertEquals(401, request("GET", "/api/short/" + code + "/stats", "", "").statusCode());
        assertEquals(403, request("GET", "/api/short/" + code + "/stats", "a", "").statusCode());
        assertEquals(401, request("PATCH", "/api/short/" + code, "", "{\"url\":\"https://example.com/b\"}").statusCode());
        assertEquals(401, request("DELETE", "/api/short/" + code, "expired", "").statusCode());
        assertEquals(403, request("DELETE", "/api/short/" + code, "a", "").statusCode());
    }

    @Test
    void everyCustomCodeAliasRequiresValidSession() throws Exception {
        for (String alias : new String[]{"customCode", "code", "slug"}) {
            var denied = request("POST", "/api/short", "expired",
                    "{\"url\":\"https://example.com\",\"" + alias + "\":\"custom\"}");
            assertEquals(403, denied.statusCode());
            assertTrue(denied.body().contains("CUSTOM_CODE_AUTH_REQUIRED"));
        }
        assertEquals(200, request("POST", "/api/short", "expired", "{\"url\":\"https://example.com\"}").statusCode());
        assertEquals(401, request("GET", "/api/short/mine", "expired", "").statusCode());
    }

    @Test
    void ownerCanManageOwnUrlButCannotReadOrMutateAnotherOwnersUrl() throws Exception {
        assertEquals(200, request("POST", "/api/short", "a",
                "{\"url\":\"https://example.com/a\",\"code\":\"Owned-A\",\"ownerUserId\":\"b\"}").statusCode());
        assertEquals("a", repository.findByCode("owned-a").ownerUserId());
        assertEquals(200, request("POST", "/api/short", "b",
                "{\"url\":\"https://example.com/b\",\"customCode\":\"owned-b\"}").statusCode());
        assertEquals(403, request("GET", "/api/short/owned-b/stats", "a", "").statusCode());
        assertEquals(403, request("PATCH", "/api/short/owned-b", "a", "{\"url\":\"https://example.com/evil\"}").statusCode());
        assertEquals(403, request("DELETE", "/api/short/owned-b", "a", "").statusCode());
        assertEquals(200, request("GET", "/api/short/owned-a/stats", "a", "").statusCode());
        assertEquals(0, DataObject.fromJson(request("GET", "/api/short/owned-a/stats", "a", "").body()).getLong("expiresAt"));
        assertEquals(200, request("PATCH", "/api/short/owned-a", "a", "{\"url\":\"https://example.com/updated\"}").statusCode());
        assertEquals("https://example.com/updated", repository.findByCode("owned-a").target());
        assertEquals(400, request("PATCH", "/api/short/owned-a", "a", "{\"url\":\"javascript:alert(1)\"}").statusCode());
        assertEquals(200, request("DELETE", "/api/short/owned-a", "a", "").statusCode());
        assertNull(repository.findByCode("owned-a"));
        assertNotNull(repository.findByCode("owned-b"));
    }

    @Test
    void limitsBeforeBodyParsingAndKeepsPublicApisAvailable() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(400, request("POST", "/api/short", "", "{\"url\":\"bad\"}").statusCode());
        }
        var denied = request("POST", "/api/short", "", "x".repeat(20_000));
        assertEquals(429, denied.statusCode());
        assertEquals("RATE_LIMITED", DataObject.fromJson(denied.body()).getString("errorCode"));
        assertTrue(Integer.parseInt(denied.headers().firstValue("Retry-After").orElseThrow()) > 0);
        for (int i = 0; i < 20; i++) {
            assertEquals(200, request("POST", "/api/short", "a", "{\"url\":\"https://example.com/a\"}").statusCode());
        }
        assertEquals(429, request("POST", "/api/short", "a", "{}").statusCode());
        assertEquals(200, request("GET", "/api/short/session", "", "").statusCode());
        assertEquals(200, request("GET", "/api/short/image/config", "", "").statusCode());
    }

    @Test
    void ownerRouteRedirectAndExpiredUrlDoNotReleaseOrLeakTarget() throws Exception {
        var oldRoute = request("GET", "/my-content", "", "");
        assertEquals(308, oldRoute.statusCode());
        assertEquals("/my", oldRoute.headers().firstValue("Location").orElseThrow());
        assertEquals(200, request("GET", "/my", "a", "").statusCode());
        long now = System.currentTimeMillis();
        repository.save(new ShortUrlService.ShortUrlEntry("expired-code", "https://example.com/secret",
                now - 1000, now - 1, 0, "a", 0));
        var expired = request("GET", "/expired-code", "", "");
        assertEquals(410, expired.statusCode());
        assertFalse(expired.body().contains("https://example.com/secret"));
        assertNotNull(repository.findByCode("expired-code"));
        assertEquals(200, request("DELETE", "/api/short/expired-code", "a", "").statusCode());
        assertEquals(404, request("GET", "/expired-code", "", "").statusCode());
    }

    @Test
    void mediaPermanentDeleteRequiresAuthenticatedOwnerAndImmediatelyRemovesMapping() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10};
        var subject = new com.norule.musicbot.domain.shorturl.QuotaSubject(
                com.norule.musicbot.domain.shorturl.AccessTier.AUTHENTICATED, "a",
                com.norule.musicbot.domain.shorturl.MediaOwnerType.DISCORD_USER, "a", "", "");
        var uploaded = media.create(new com.norule.musicbot.service.shorturl.ImageShareService.Upload(png, false, "", 60_000), subject);
        assertTrue(uploaded.isSuccess());
        String code = uploaded.imageShare().code();
        assertEquals(401, request("DELETE", "/api/short/" + code, "", "").statusCode());
        assertEquals(403, request("DELETE", "/api/short/" + code, "b", "").statusCode());
        assertEquals(200, request("DELETE", "/api/short/" + code, "a", "").statusCode());
        assertEquals(404, request("DELETE", "/api/short/" + code, "a", "").statusCode());
        assertEquals(404, request("GET", "/" + code, "", "").statusCode());
        assertTrue(java.nio.file.Files.exists(temp.resolve("short-url-expired").resolve(uploaded.imageShare().storageName())));
    }

    private void session(String user, long expires) {
        sessions.sessions().put(user, new WebSessionManager.WebSession(user, user, "", "", "", expires));
    }

    private HttpResponse<String> request(String method, String path, String session, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        if (!session.isBlank()) builder.header("Cookie", "norule_session=" + session);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
