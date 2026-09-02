package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomShortUrlGatewayTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsSpecificValidationAndCollisionErrors() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ShortUrlService service = new ShortUrlService(new SqliteShortUrlRepository(
                tempDir.resolve("custom-code-gateway.db")));
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "http://127.0.0.1:" + port
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(service, () -> config);
        HttpClient client = HttpClient.newHttpClient();
        try {
            gateway.syncWithConfig();

            HttpResponse<String> invalid = post(client, port,
                    "{\"url\":\"https://example.com/invalid\",\"customCode\":\"bad/code\"}");
            HttpResponse<String> reserved = post(client, port,
                    "{\"url\":\"https://example.com/reserved\",\"customCode\":\"My-Content\"}");
            HttpResponse<String> created = post(client, port,
                    "{\"url\":\"https://example.com/created\",\"customCode\":\"My-Code\"}");
            HttpResponse<String> duplicate = post(client, port,
                    "{\"url\":\"https://example.com/duplicate\",\"customCode\":\"MY-CODE\"}");

            assertEquals(400, invalid.statusCode());
            assertTrue(invalid.body().contains("INVALID_CUSTOM_CODE"));
            assertEquals(400, reserved.statusCode());
            assertTrue(reserved.body().contains("RESERVED_CUSTOM_CODE"));
            assertEquals(200, created.statusCode());
            assertTrue(created.body().contains("\"code\":\"my-code\""));
            assertEquals(409, duplicate.statusCode());
            assertTrue(duplicate.body().contains("CUSTOM_CODE_ALREADY_EXISTS"));
        } finally {
            gateway.shutdown();
        }
    }

    private HttpResponse<String> post(HttpClient client, int port, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/short"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
