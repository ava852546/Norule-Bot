package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import com.norule.musicbot.service.shorturl.ImageShareService;
import com.norule.musicbot.service.shorturl.RateLimitService;
import com.norule.musicbot.shorturl.InMemoryRateLimitStore;
import com.norule.musicbot.shorturl.SqliteImageShareRepository;
import com.norule.musicbot.shorturl.SqliteMediaBlobRepository;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    void mediaFailuresCleanTemporaryFilesReleasePermitsAndThenReturn429(@TempDir Path tempDir) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path database = tempDir.resolve("gateway-media.db");
        Path temporary = tempDir.resolve("uploads");
        OwnerRepository shortUrls = new OwnerRepository();
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                tempDir.resolve("media"), temporary, tempDir.resolve("archive"));
        ImageShareService imageService = new ImageShareService(
                new SqliteImageShareRepository(database),
                new SqliteMediaBlobRepository(database),
                shortUrls,
                storage,
                new ImageShareService.Options(
                        true, 60_000L, 86_400_000L, 1_048_576L,
                        1_048_576L, 300_000L, 86_400_000L, 60_000L, 7),
                null,
                null);
        RateLimitService rateLimits = new RateLimitService(
                new InMemoryRateLimitStore(),
                new RateLimitService.Options(true, 3, 100, 200, 100, 100, 1, 1));
        ShortUrlService service = new ShortUrlService(
                shortUrls,
                new ShortUrlService.Options(true, 86_400_000L, 60_000L,
                        "http://127.0.0.1:" + port, 7, false),
                imageService, null, rateLimits);
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "http://127.0.0.1:" + port
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(service, () -> config);
        try {
            gateway.syncWithConfig();
            assertEquals(413, postDeclaredOversizedMultipart(port, 20L * 1024L * 1024L));
            assertFalse(Files.exists(temporary));
            MultipartResponse first = postMultipart(port, "first.png", new byte[]{1, 2, 3});
            MultipartResponse second = postMultipart(port, "second.png", new byte[]{1, 2, 3});
            MultipartResponse limited = postMultipart(port, "third.png", new byte[]{1, 2, 3});

            assertEquals(400, first.statusCode());
            assertEquals(400, second.statusCode());
            assertTrue(first.body().contains("UNSUPPORTED_MEDIA"));
            assertTrue(second.body().contains("UNSUPPORTED_MEDIA"));
            assertEquals(429, limited.statusCode());
            assertFalse(limited.retryAfter().isBlank());
            assertEquals(0L, regularFileCount(temporary));
        } finally {
            gateway.shutdown();
        }
    }

    @Test
    void returnsUnified429BeforeParsingAnotherShortUrlRequest() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        RateLimitService rateLimits = new RateLimitService(
                new InMemoryRateLimitStore(),
                new RateLimitService.Options(true, 100, 100, 200, 2, 100, 2, 3));
        ShortUrlService service = new ShortUrlService(
                new OwnerRepository(),
                new ShortUrlService.Options(true, 86_400_000L, 60_000L,
                        "http://127.0.0.1:" + port, 7, false),
                null, null, rateLimits);
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "http://127.0.0.1:" + port
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(service, () -> config);
        HttpClient client = HttpClient.newHttpClient();
        try {
            gateway.syncWithConfig();
            assertEquals(200, post(client, port,
                    "{\"url\":\"https://example.com/one\"}").statusCode());
            assertEquals(200, post(client, port,
                    "{\"url\":\"https://example.com/two\"}").statusCode());
            HttpResponse<String> limited = post(client, port,
                    "{\"url\":\"https://example.com/three\"}");

            assertEquals(429, limited.statusCode());
            assertTrue(limited.headers().firstValue("Retry-After").isPresent());
            assertTrue(limited.body().contains("\"error\":\"RATE_LIMITED\""));
            assertTrue(limited.body().contains("\"retryAfter\":"));
            assertFalse(limited.body().contains("bucket"));
        } finally {
            gateway.shutdown();
        }
    }

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
    void validatesAndNormalizesCustomCodesAtTheGateway() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OwnerRepository repository = new OwnerRepository();
        ShortUrlService service = new ShortUrlService(repository);
        BotConfig.ShortUrl config = BotConfig.ShortUrl.fromMap(Map.of(
                "enabled", true,
                "bindPort", port,
                "publicBaseUrl", "http://127.0.0.1:" + port
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlGatewayServer gateway = new ShortUrlGatewayServer(service, () -> config);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        try {
            gateway.syncWithConfig();
            HttpResponse<String> invalid = post(client, port,
                    "{\"url\":\"https://example.com/invalid\",\"customCode\":\"bad/code\"}");
            HttpResponse<String> reserved = post(client, port,
                    "{\"url\":\"https://example.com/reserved\",\"customCode\":\"Stats\"}");
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
        Path database = tempDir.resolve("short-url.db");
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(tempDir.resolve("media"));
        ImageShareService imageService = new ImageShareService(
                images,
                new SqliteMediaBlobRepository(database),
                shortUrls,
                storage,
                new ImageShareService.Options(
                        true, 60_000L, 86_400_000L, 1_048_576L, 60_000L, 7),
                null,
                null
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
            String applicationHtml = publicPage.body().replaceAll(
                    "(?i)<script[^>]*local\\.adguard\\.org[^>]*></script>", "");
            assertFalse(applicationHtml.contains("media01"));
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

    private HttpResponse<String> post(HttpClient client, int port, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/short"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private MultipartResponse postMultipart(int port, String filename, byte[] content) throws Exception {
        String boundary = "NoRuleBoundary";
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(content, 0, body, prefix.length, content.length);
        System.arraycopy(suffix, 0, body, prefix.length + content.length, suffix.length);
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/short/image").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (var output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        var responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = responseStream == null
                ? "" : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
        if (responseStream != null) {
            responseStream.close();
        }
        String retryAfter = connection.getHeaderField("Retry-After");
        connection.disconnect();
        return new MultipartResponse(status, responseBody, retryAfter == null ? "" : retryAfter);
    }

    private int postDeclaredOversizedMultipart(int port, long declaredLength) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            String headers = "POST /api/short/image HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: multipart/form-data; boundary=NoRuleBoundary\r\n"
                    + "Content-Length: " + declaredLength + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertTrue(statusLine != null && statusLine.startsWith("HTTP/1.1 "));
            return Integer.parseInt(statusLine.split(" ", 3)[1]);
        }
    }

    private long regularFileCount(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private record MultipartResponse(int statusCode, String body, String retryAfter) {
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
