package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.domain.shorturl.ShortUrlCreationError;
import com.norule.musicbot.shorturl.SqliteShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyUppercaseShortUrlGatewayTest {
    @TempDir
    Path tempDir;

    @Test
    void keepsLegacyUppercasePathsExactForRedirectAndStatistics() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        SqliteShortUrlRepository repository = new SqliteShortUrlRepository(
                tempDir.resolve("legacy-uppercase-gateway.db"));
        long now = System.currentTimeMillis();
        repository.save(new ShortUrlService.ShortUrlEntry(
                "AbC123", "https://example.com/legacy", now, now + 60_000L,
                0L, "owner-123", 0L));
        ShortUrlService service = new ShortUrlService(repository);
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "http://127.0.0.1:" + port
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(
                service, () -> config, exchange -> "owner-123");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            gateway.syncWithConfig();

            HttpResponse<String> redirect = get(client, port, "/AbC123");
            HttpResponse<String> lowercasePath = get(client, port, "/abc123");
            HttpResponse<String> statistics = get(client, port, "/AbC123?stats");
            ShortUrlService.CreationOutcome duplicate = service.createWithOutcome(
                    "https://example.com/new", "abc123", "owner-123", "127.0.0.1");

            assertEquals(302, redirect.statusCode());
            assertEquals("https://example.com/legacy",
                    redirect.headers().firstValue("Location").orElseThrow());
            assertEquals(404, lowercasePath.statusCode());
            assertEquals(200, statistics.statusCode());
            assertEquals(ShortUrlCreationError.CUSTOM_CODE_ALREADY_EXISTS, duplicate.error());
            assertNull(duplicate.entry());
        } finally {
            gateway.shutdown();
        }
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
