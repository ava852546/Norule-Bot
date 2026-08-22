package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import com.norule.musicbot.service.shorturl.ImageShareService;
import com.norule.musicbot.shorturl.SqliteImageShareRepository;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlGatewayServerTest {
    @Test
    void rejectsOversizedShortUrlRequestAtTheGateway() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "https://s.example.com"
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(
                new ShortUrlService(new EmptyRepository()), () -> config);
        try {
            gateway.syncWithConfig();
            byte[] oversized = new byte[16 * 1024 + 1];
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/short"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(413, response.statusCode());
            assertTrue(response.body().contains("REQUEST_BODY_TOO_LARGE"));
        } finally {
            gateway.shutdown();
        }
    }

    @Test
    void rendersAnExpiredSharePage() {
        String html = ShortUrlGatewayServer.buildImageExpiredPage();

        assertTrue(html.contains("410"));
        assertTrue(html.contains("此分享已到期"));
    }

    @Test
    void parsesSingleByteRangesForVideoStreaming() {
        assertEquals(new ShortUrlGatewayServer.ByteRange(0L, 99L),
                ShortUrlGatewayServer.parseByteRange("bytes=0-99", 1000L));
        assertEquals(new ShortUrlGatewayServer.ByteRange(900L, 999L),
                ShortUrlGatewayServer.parseByteRange("bytes=-100", 1000L));
        assertEquals(new ShortUrlGatewayServer.ByteRange(500L, 999L),
                ShortUrlGatewayServer.parseByteRange("bytes=500-", 1000L));
        assertEquals(new ShortUrlGatewayServer.ByteRange(0L, 9L),
                ShortUrlGatewayServer.parseByteRange("BYTES=0-9", 1000L));
        assertNull(ShortUrlGatewayServer.parseByteRange("bytes=1000-", 1000L));
        assertNull(ShortUrlGatewayServer.parseByteRange("bytes=0-1,4-5", 1000L));
    }

    @Test
    void recognizesStatsParameterButNotAnEmptyTrailingQuery() {
        assertTrue(ShortUrlGatewayServer.isStatisticsQuery("stats"));
        assertTrue(ShortUrlGatewayServer.isStatisticsQuery("stats&__nr_auth=ticket"));
        assertFalse(ShortUrlGatewayServer.isStatisticsQuery(""));
        assertFalse(ShortUrlGatewayServer.isStatisticsQuery(null));
        assertFalse(ShortUrlGatewayServer.isStatisticsQuery("view=stats"));
    }

    @Test
    void protectsOwnerApisAndDoesNotCountStatsOrDashboardViews() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OwnerRepository repository = new OwnerRepository();
        ShortUrlService service = new ShortUrlService(repository);
        service.create("https://example.com/owned", "owned-code", "owner-a", "127.0.0.1");
        service.create("https://example.com/other", "other-code", "owner-b", "127.0.0.1");
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "http://127.0.0.1:" + port
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(
                service,
                () -> config,
                exchange -> exchange.getRequestHeaders().getFirst("X-Test-User")
        );
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            gateway.syncWithConfig();

            HttpResponse<String> anonymousStats = get(client, port, "/api/short/owned-code/stats", "");
            HttpResponse<String> otherStats = get(client, port, "/api/short/owned-code/stats", "owner-b");
            HttpResponse<String> ownerStats = get(client, port, "/api/short/owned-code/stats", "owner-a");
            HttpResponse<String> ownerPage = get(client, port, "/owned-code?stats", "owner-a");
            HttpResponse<String> otherPage = get(client, port, "/owned-code?stats", "owner-b");
            HttpResponse<String> ownedContent = get(client, port, "/api/short/mine?type=ALL&page=0&size=20", "owner-a");

            assertEquals(401, anonymousStats.statusCode());
            assertEquals(403, otherStats.statusCode());
            assertEquals(200, ownerStats.statusCode());
            assertTrue(ownerStats.body().contains("\"viewCount\":0"));
            assertEquals(200, ownerPage.statusCode());
            assertEquals(403, otherPage.statusCode());
            assertEquals(200, ownedContent.statusCode());
            assertTrue(ownedContent.body().contains("owned-code"));
            assertFalse(ownedContent.body().contains("other-code"));
            assertEquals(0L, repository.findByCode("owned-code").getViewCount());

            HttpResponse<String> publicView = get(client, port, "/owned-code", "");
            assertEquals(302, publicView.statusCode());
            assertEquals(1L, repository.findByCode("owned-code").getViewCount());
        } finally {
            gateway.shutdown();
        }
    }

    @Test
    void separatesPublicMediaMetadataFromOwnerStatistics(@TempDir Path tempDir) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OwnerRepository shortUrls = new OwnerRepository();
        SqliteImageShareRepository images = new SqliteImageShareRepository(tempDir.resolve("short-url.db"));
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(tempDir.resolve("media"));
        ImageShareService imageService = new ImageShareService(
                images,
                shortUrls,
                storage,
                new ImageShareService.Options(
                        true, 60_000L, 86_400_000L, 1_048_576L, 60_000L, 7)
        );
        long now = System.currentTimeMillis();
        ImageShare media = new ImageShare(
                "media01", "media01.png", "image/png", 8L, now, now + 60_000L,
                "", "hash"
        ).withOwnership(MediaOwnerType.DISCORD_USER, "owner-a", "quota-a");
        storage.save(media, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        images.save(media);
        ShortUrlService service = new ShortUrlService(
                shortUrls,
                new ShortUrlService.Options(true, 86_400_000L, 60_000L,
                        "http://127.0.0.1:" + port, 7, false),
                imageService
        );
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "http://127.0.0.1:" + port
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(
                service, () -> config,
                exchange -> exchange.getRequestHeaders().getFirst("X-Test-User"));
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            gateway.syncWithConfig();
            HttpResponse<String> publicMetadata = get(client, port, "/api/short/media01", "");
            HttpResponse<String> ownerStats = get(client, port, "/api/short/media01/stats", "owner-a");
            HttpResponse<String> otherStats = get(client, port, "/api/short/media01/stats", "owner-b");
            HttpResponse<String> mediaList = get(client, port,
                    "/api/short/mine?type=MEDIA&status=ACTIVE&sort=createdAt,desc&page=0&size=20", "owner-a");
            HttpResponse<String> statsPage = get(client, port, "/media01?stats", "owner-a");

            assertEquals(200, publicMetadata.statusCode());
            assertTrue(publicMetadata.body().contains("\"mediaType\":\"IMAGE\""));
            assertFalse(publicMetadata.body().contains("viewCount"));
            assertFalse(publicMetadata.body().contains("owner"));
            assertFalse(publicMetadata.body().contains("fileSize"));
            assertEquals(200, ownerStats.statusCode());
            assertTrue(ownerStats.body().contains("\"contentType\":\"image/png\""));
            assertTrue(ownerStats.body().contains("\"fileSize\":8"));
            assertEquals(403, otherStats.statusCode());
            assertEquals(200, mediaList.statusCode());
            assertTrue(mediaList.body().contains("media01"));
            assertEquals(200, statsPage.statusCode());
            assertEquals(0L, images.findByCode("media01").viewCount());

            HttpResponse<String> publicPage = get(client, port, "/media01", "");
            assertEquals(200, publicPage.statusCode());
            assertFalse(publicPage.body().contains("OWNER STATISTICS"));
            assertFalse(publicPage.body().contains("media01"));
            assertEquals(1L, images.findByCode("media01").viewCount());
        } finally {
            gateway.shutdown();
        }
    }

    private HttpResponse<String> get(HttpClient client, int port, String path, String userId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET();
        if (userId != null && !userId.isBlank()) {
            request.header("X-Test-User", userId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class EmptyRepository implements ShortUrlRepository {
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

    private static final class OwnerRepository implements ShortUrlRepository {
        private final Map<String, ShortUrlService.ShortUrlEntry> entries = new LinkedHashMap<>();

        @Override
        public ShortUrlService.ShortUrlEntry findByCode(String code) {
            return entries.get(code);
        }

        @Override
        public ShortUrlService.ShortUrlEntry findActiveByTarget(String target, long nowMillis) {
            return entries.values().stream()
                    .filter(entry -> entry.target().equals(target) && entry.expiresAt() > nowMillis)
                    .max(Comparator.comparingLong(ShortUrlService.ShortUrlEntry::createdAt))
                    .orElse(null);
        }

        @Override
        public List<ShortUrlService.ShortUrlEntry> findByOwnerUserId(String ownerUserId, int offset, int limit) {
            return entries.values().stream()
                    .filter(entry -> ownerUserId.equals(entry.ownerUserId()))
                    .sorted(Comparator.comparingLong(ShortUrlService.ShortUrlEntry::createdAt).reversed())
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countByOwnerUserId(String ownerUserId) {
            return entries.values().stream().filter(entry -> ownerUserId.equals(entry.ownerUserId())).count();
        }

        @Override
        public void save(ShortUrlService.ShortUrlEntry entry) {
            entries.put(entry.code(), entry);
        }

        @Override
        public void deleteByCode(String code) {
            entries.remove(code);
        }

        @Override
        public int cleanupExpired(long nowMillis) {
            int size = entries.size();
            entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= nowMillis);
            return size - entries.size();
        }

        @Override
        public long incrementViewCount(String code) {
            return incrementViewCount(code, 0L);
        }

        @Override
        public long incrementViewCount(String code, long lastAccessedAt) {
            ShortUrlService.ShortUrlEntry entry = entries.get(code);
            if (entry == null) return 0L;
            ShortUrlService.ShortUrlEntry updated = entry.withViewMetrics(entry.viewCount() + 1L, lastAccessedAt);
            entries.put(code, updated);
            return updated.viewCount();
        }

        @Override
        public Long findLogChannelId() {
            return null;
        }

        @Override
        public void saveLogChannelId(Long channelId) {
        }
    }
}
