package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.QuotaSubject;
import com.norule.musicbot.domain.shorturl.ShortUrlStatistics;
import com.norule.musicbot.service.shorturl.ImageShareService;
import com.norule.musicbot.service.shorturl.AnonymousDeviceIdentityService;
import com.norule.musicbot.service.shorturl.MediaPasswordAttemptGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShortUrlGatewayServer {
    private static final String BIND_HOST = "0.0.0.0";
    private static final long MULTIPART_OVERHEAD_BYTES = 128L * 1024L;
    private static final long IMAGE_ACCESS_DURATION_MILLIS = 60L * 60L * 1000L;
    private static final long VIEW_DEDUPLICATION_MILLIS = 60L * 1000L;
    private static final String IMAGE_ACCESS_COOKIE = "nr_image_access";
    private static final String ANONYMOUS_DEVICE_COOKIE = "nr_anon_device";
    private static final SecureRandom IMAGE_ACCESS_RANDOM = new SecureRandom();
    private static final DateTimeFormatter IMAGE_DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Pattern MULTIPART_BOUNDARY = Pattern.compile("boundary=(?:\\\"([^\\\"]+)\\\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_DISPOSITION_NAME = Pattern.compile("(?:^|;)\\s*name=\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Set<String> RESERVED_PATHS = Set.of(
            "api", "assets", "static", "web", "dashboard", "short-url", "index", "404"
    );

    private final ShortUrlService shortUrlService;
    private final Supplier<BotConfig.ShortUrl> configSupplier;
    private final Function<HttpExchange, String> authenticatedUserResolver;
    private final BiFunction<String, String, String> authenticationLoginUrlFactory;
    private final Function<HttpExchange, String> authenticationHandoffResolver;
    private final Consumer<HttpExchange> authenticationLogoutHandler;
    private final Map<String, ImageAccessGrant> imageAccessGrants = new ConcurrentHashMap<>();
    private final Map<String, Long> recentViewers = new ConcurrentHashMap<>();
    private volatile HttpServer server;
    private volatile ScheduledExecutorService maintenanceExecutor;
    private volatile int bindPort = -1;

    public ShortUrlGatewayServer(ShortUrlService shortUrlService, Supplier<BotConfig.ShortUrl> configSupplier) {
        this(shortUrlService, configSupplier, exchange -> "");
    }

    public ShortUrlGatewayServer(ShortUrlService shortUrlService,
                                 Supplier<BotConfig.ShortUrl> configSupplier,
                                 Function<HttpExchange, String> authenticatedUserResolver) {
        this(shortUrlService, configSupplier, authenticatedUserResolver,
                (returnTo, anonymousDeviceToken) -> "/auth/login?returnTo=" + java.net.URLEncoder.encode(
                        returnTo, StandardCharsets.UTF_8), exchange -> "", exchange -> { });
    }

    public ShortUrlGatewayServer(ShortUrlService shortUrlService,
                                 Supplier<BotConfig.ShortUrl> configSupplier,
                                 Function<HttpExchange, String> authenticatedUserResolver,
                                 BiFunction<String, String, String> authenticationLoginUrlFactory,
                                 Function<HttpExchange, String> authenticationHandoffResolver) {
        this(shortUrlService, configSupplier, authenticatedUserResolver, authenticationLoginUrlFactory,
                authenticationHandoffResolver, exchange -> { });
    }

    public ShortUrlGatewayServer(ShortUrlService shortUrlService,
                                 Supplier<BotConfig.ShortUrl> configSupplier,
                                 Function<HttpExchange, String> authenticatedUserResolver,
                                 BiFunction<String, String, String> authenticationLoginUrlFactory,
                                 Function<HttpExchange, String> authenticationHandoffResolver,
                                 Consumer<HttpExchange> authenticationLogoutHandler) {
        if (shortUrlService == null) {
            throw new IllegalArgumentException("shortUrlService cannot be null");
        }
        if (configSupplier == null) {
            throw new IllegalArgumentException("configSupplier cannot be null");
        }
        this.shortUrlService = shortUrlService;
        this.configSupplier = configSupplier;
        this.authenticatedUserResolver = authenticatedUserResolver == null ? exchange -> ""
                : authenticatedUserResolver;
        this.authenticationLoginUrlFactory = authenticationLoginUrlFactory == null ? (returnTo, deviceToken) -> ""
                : authenticationLoginUrlFactory;
        this.authenticationHandoffResolver = authenticationHandoffResolver == null ? exchange -> ""
                : authenticationHandoffResolver;
        this.authenticationLogoutHandler = authenticationLogoutHandler == null ? exchange -> { }
                : authenticationLogoutHandler;
    }

    public synchronized void syncWithConfig() {
        BotConfig.ShortUrl config = config();
        if (!config.isEnabled()) {
            stop();
            return;
        }
        if (server != null && bindPort == config.getBindPort()) {
            return;
        }
        stop();
        start(config);
    }

    public synchronized void shutdown() {
        stop();
    }

    private BotConfig.ShortUrl config() {
        BotConfig.ShortUrl config = configSupplier.get();
        return config == null ? BotConfig.ShortUrl.defaultValues() : config;
    }

    private void start(BotConfig.ShortUrl config) {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(BIND_HOST, config.getBindPort()), 0);
            created.createContext("/api/short", this::handleShortUrlApi);
            created.createContext("/web/", this::handleWebAsset);
            created.createContext("/", this::handleResolve);
            created.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "NoRule-ShortUrl");
                t.setDaemon(true);
                return t;
            }));
            created.start();
            try {
                shortUrlService.cleanupExpired();
            } catch (RuntimeException exception) {
                System.err.println("[NoRule] Initial short URL media reconciliation failed: "
                        + exception.getClass().getSimpleName());
            }
            ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "NoRule-ShortUrl-Maintenance");
                thread.setDaemon(true);
                return thread;
            });
            maintenance.scheduleWithFixedDelay(() -> {
                try {
                    shortUrlService.cleanupExpired();
                } catch (RuntimeException exception) {
                    System.err.println("[NoRule] Short URL media maintenance failed: "
                            + exception.getClass().getSimpleName());
                }
            }, config.getCleanupIntervalMinutes(), config.getCleanupIntervalMinutes(), TimeUnit.MINUTES);
            this.server = created;
            this.maintenanceExecutor = maintenance;
            this.bindPort = config.getBindPort();
            System.out.println("[NoRule] Short URL gateway started on http://" + BIND_HOST + ":" + config.getBindPort());
        } catch (Exception e) {
            System.out.println("[NoRule] Failed to start short URL gateway: " + e.getMessage());
        }
    }

    private void stop() {
        ScheduledExecutorService maintenance = this.maintenanceExecutor;
        if (maintenance != null) {
            maintenance.shutdownNow();
            this.maintenanceExecutor = null;
        }
        HttpServer current = this.server;
        if (current == null) {
            return;
        }
        current.stop(0);
        this.server = null;
        System.out.println("[NoRule] Short URL gateway stopped.");
    }

    private void handleResolve(HttpExchange exchange) throws IOException {
        ensureAnonymousDevice(exchange);
        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            if (!isGetOrHead(exchange)) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            if (parseUrlEncoded(exchange.getRequestURI().getRawQuery()).containsKey("__nr_auth")) {
                resolveAuthenticationHandoff(exchange);
                redirect(exchange, shortUrlService.publicBaseUrl() + "/");
                return;
            }
            sendHtml(exchange, 200, loadTemplate("web/short-url.html"));
            return;
        }
        if (rawPath.startsWith("/api/")) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }
        String code = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (code.isBlank() || code.contains("/") || RESERVED_PATHS.contains(code.toLowerCase(Locale.ROOT))) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }

        if (isStatisticsQuery(exchange.getRequestURI().getRawQuery())) {
            handleStatistics(exchange, code);
            return;
        }

        ImageShare imageShare = shortUrlService.resolveImageShare(code);
        if (imageShare != null) {
            handleImageShareResolve(exchange, imageShare);
            return;
        }

        if (shortUrlService.findExpiredImageShare(code) != null) {
            sendHtml(exchange, 410, buildImageExpiredPage());
            return;
        }

        if (!isGetOrHead(exchange)) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        ShortUrlService.ShortUrlEntry entry = shortUrlService.resolve(code);
        if (entry == null || entry.target().isBlank()) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String address = clientAddress(exchange);
            if (shouldRecordView("url", code, address)) {
                shortUrlService.recordView(entry, address, userAgent(exchange));
            }
        }
        exchange.getResponseHeaders().set("Location", entry.target());
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleShortUrlApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/short/session".equals(path)) {
            handleSessionStatus(exchange);
            return;
        }
        if ("/api/short/session/login".equals(path)) {
            handleSessionLogin(exchange);
            return;
        }
        if ("/api/short/session/logout".equals(path)) {
            handleSessionLogout(exchange);
            return;
        }
        String contentPathPrefix = "/api/short/image/content/";
        if (path.startsWith(contentPathPrefix)) {
            handleImageShareContent(exchange, path.substring(contentPathPrefix.length()));
            return;
        }
        String accessPathPrefix = "/api/short/image/access/";
        if (path.startsWith(accessPathPrefix)) {
            handleImageShareAccess(exchange, path.substring(accessPathPrefix.length()));
            return;
        }
        if ("/api/short/image/config".equals(path)) {
            handleImageShareConfig(exchange);
            return;
        }
        if ("/api/short/image".equals(path)) {
            handleCreateImageShare(exchange);
            return;
        }
        if ("/api/short".equals(path)) {
            handleCreateShortUrl(exchange);
            return;
        }
        sendJson(exchange, 404, DataObject.empty()
                .put("error", "Not Found")
                .put("errorCode", "NOT_FOUND")
                .toString());
    }

    private void handleSessionStatus(HttpExchange exchange) throws IOException {
        if (!isGetOrHead(exchange)) {
            sendJson(exchange, 405, DataObject.empty()
                    .put("error", "Method Not Allowed")
                    .put("errorCode", "METHOD_NOT_ALLOWED")
                    .toString());
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        sendJson(exchange, 200, DataObject.empty()
                .put("authenticated", !authenticatedUserId(exchange).isBlank())
                .toString());
    }

    private void handleSessionLogin(HttpExchange exchange) throws IOException {
        if (!isGetOrHead(exchange)) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        String homeUrl = shortUrlService.publicBaseUrl() + "/";
        if (!authenticatedUserId(exchange).isBlank()) {
            redirect(exchange, homeUrl);
            return;
        }
        AnonymousDeviceIdentityService.DeviceIdentity device = ensureAnonymousDevice(exchange);
        String deviceToken = device == null ? readCookie(exchange, ANONYMOUS_DEVICE_COOKIE) : device.token();
        String loginUrl = authenticationLoginUrlFactory.apply(
                homeUrl, deviceToken);
        if (loginUrl == null || loginUrl.isBlank()) {
            sendText(exchange, 503, "Authentication unavailable");
            return;
        }
        redirect(exchange, loginUrl);
    }

    private void handleSessionLogout(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty()
                    .put("error", "Method Not Allowed")
                    .put("errorCode", "METHOD_NOT_ALLOWED")
                    .toString());
            return;
        }
        authenticationLogoutHandler.accept(exchange);
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        sendJson(exchange, 200, DataObject.empty().put("authenticated", false).toString());
    }

    private void handleCreateShortUrl(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty()
                    .put("error", "Method Not Allowed")
                    .put("errorCode", "METHOD_NOT_ALLOWED")
                    .toString());
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        Map<String, String> form = parseRequestBody(body, contentType);
        String target = form.getOrDefault("url", "").trim();
        String customCode = form.getOrDefault("customCode", form.getOrDefault("code", form.getOrDefault("slug", ""))).trim();
        if (target.isBlank()) {
            sendJson(exchange, 400, DataObject.empty()
                    .put("error", "Missing url")
                    .put("errorCode", "MISSING_URL")
                    .toString());
            return;
        }

        ShortUrlService.ShortUrlEntry created = shortUrlService.create(
                target,
                customCode,
                authenticatedUserId(exchange),
                clientAddress(exchange)
        );
        if (created == null) {
            sendJson(exchange, 400, DataObject.empty()
                    .put("error", "Invalid url or code")
                    .put("errorCode", "INVALID_URL_OR_CODE")
                    .toString());
            return;
        }

        sendJson(exchange, 200, DataObject.empty()
                .put("code", created.getCode())
                .put("shortUrl", shortUrlService.toPublicUrl(created.getCode()))
                .put("targetUrl", created.getTarget())
                .put("viewCount", created.getViewCount())
                .toString());
    }

    private void handleImageShareConfig(HttpExchange exchange) throws IOException {
        if (!isGetOrHead(exchange)) {
            sendJson(exchange, 405, DataObject.empty()
                    .put("error", "Method Not Allowed")
                    .put("errorCode", "METHOD_NOT_ALLOWED")
                    .toString());
            return;
        }
        QuotaSubject quotaSubject = resolveUploadQuotaSubject(exchange);
        ImageShareService.Options options = shortUrlService.imageShareOptions();
        if (options == null) {
            sendJson(exchange, 200, DataObject.empty().put("enabled", false).toString());
            return;
        }
        long effectiveMaxRetentionMillis = shortUrlService.mediaMaxRetentionMillis(quotaSubject);
        sendJson(exchange, 200, DataObject.empty()
                .put("enabled", options.enabled())
                .put("defaultRetentionHours", options.defaultRetentionMillis() / (60L * 60L * 1000L))
                .put("maxRetentionDays", effectiveMaxRetentionMillis / (24L * 60L * 60L * 1000L))
                .put("accessTier", quotaSubject == null ? "ANONYMOUS" : quotaSubject.accessTier().name())
                .put("maxFileSizeBytes", options.maxFileSizeBytes())
                .put("maxFileSizeMb", options.maxFileSizeBytes() / (1024L * 1024L))
                .put("maxVideoFileSizeBytes", options.maxVideoFileSizeBytes())
                .put("maxVideoFileSizeMb", options.maxVideoFileSizeBytes() / (1024L * 1024L))
                .put("maxVideoDurationSeconds", options.maxVideoDurationMillis() / 1000L)
                .put("expiredShareRetentionDays", options.expiredShareRetentionMillis() / (24L * 60L * 60L * 1000L))
                .put("allowDateDefaultPassword", options.allowDateDefaultPassword())
                .put("minPasswordLength", options.minPasswordLength())
                .put("maxPasswordLength", options.maxPasswordLength())
                .toString());
    }

    private void handleCreateImageShare(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendImageError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed");
            return;
        }
        QuotaSubject quotaSubject = resolveUploadQuotaSubject(exchange);
        ImageShareService.Options options = shortUrlService.imageShareOptions();
        if (options == null || !options.enabled()) {
            sendImageError(exchange, 503, "IMAGE_SHARING_DISABLED", "Image sharing is disabled");
            return;
        }

        try {
            MultipartForm form = parseMultipartForm(exchange, options.maxUploadSizeBytes());
            byte[] media = form.firstFile("image");
            if (media == null) {
                sendImageError(exchange, 400, "IMAGE_REQUIRED", "An image or video file is required");
                return;
            }
            ExpirationRequest expiration = parseExpirationRequest(form, options);
            if (expiration == null) {
                sendImageError(exchange, 400, "RETENTION_TOO_LONG", "The requested retention is outside the allowed range");
                return;
            }
            boolean passwordProtected = Boolean.parseBoolean(form.value("passwordProtected"));
            ImageShareService.UploadResult result = shortUrlService.createImageShare(
                    new ImageShareService.Upload(
                            media,
                            passwordProtected,
                            form.value("password"),
                            expiration.retentionMillis(),
                            expiration.expiresAtMillis()
                    ),
                    clientAddress(exchange),
                    userAgent(exchange),
                    quotaSubject
            );
            if (!result.isSuccess()) {
                sendImageUploadFailure(exchange, result.error());
                return;
            }
            ImageShare created = result.imageShare();
            sendJson(exchange, 200, DataObject.empty()
                    .put("code", created.code())
                    .put("shortUrl", shortUrlService.toPublicUrl(created.code()))
                    .put("expiresAt", created.expiresAt())
                    .put("passwordProtected", created.isPasswordProtected())
                    .put("viewCount", created.viewCount())
                    .toString());
        } catch (RequestBodyTooLargeException e) {
            sendImageError(exchange, 413, "MEDIA_TOO_LARGE", "The uploaded file exceeds the configured size limit");
        } catch (InvalidMultipartException e) {
            sendImageError(exchange, 400, "INVALID_MEDIA_UPLOAD", "Invalid media upload request");
        }
    }

    private void handleImageShareResolve(HttpExchange exchange, ImageShare imageShare) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        if (imageShare.isPasswordProtected() && !hasImageAccess(exchange, imageShare)) {
            sendImagePasswordPage(exchange, imageShare.code());
            return;
        }
        ImageShare viewed = imageShare;
        if ("GET".equalsIgnoreCase(method)) {
            String address = clientAddress(exchange);
            if (shouldRecordView("media", imageShare.code(), address)) {
                ImageShare recorded = shortUrlService.recordImageShareView(
                        imageShare,
                        address,
                        userAgent(exchange)
                );
                if (recorded != null) {
                    viewed = recorded;
                }
            }
        }
        sendHtml(exchange, 200, buildImageViewPage(viewed));
    }

    private void handleStatistics(HttpExchange exchange, String code) throws IOException {
        if (!isGetOrHead(exchange)) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        Map<String, String> query = parseUrlEncoded(exchange.getRequestURI().getRawQuery());
        if (query.containsKey("__nr_auth")) {
            String transferredUserId = resolveAuthenticationHandoff(exchange);
            if (!transferredUserId.isBlank()) {
                redirect(exchange, shortUrlService.toPublicUrl(code) + "?stats");
                return;
            }
        }
        String userId = authenticatedUserId(exchange);
        if (userId.isBlank()) {
            String returnTo = shortUrlService.toPublicUrl(code) + "?stats";
            String loginUrl = authenticationLoginUrlFactory.apply(
                    returnTo, readCookie(exchange, ANONYMOUS_DEVICE_COOKIE));
            if (loginUrl == null || loginUrl.isBlank()) {
                sendText(exchange, 503, "Authentication unavailable");
                return;
            }
            redirect(exchange, loginUrl);
            return;
        }
        ShortUrlStatistics statistics = shortUrlService.findStatisticsForOwner(code, userId);
        if (statistics == null) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        sendHtml(exchange, 200, buildStatisticsPage(statistics));
    }

    static boolean isStatisticsQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return false;
        }
        for (String pair : rawQuery.split("&")) {
            String key = pair.split("=", 2)[0];
            if ("stats".equals(key)) {
                return true;
            }
        }
        return false;
    }

    static String buildStatisticsPage(ShortUrlStatistics statistics) {
        String resourceName = statistics.resourceType() == ShortUrlStatistics.ResourceType.MEDIA_SHARE
                ? "媒體分享" : "短網址";
        String lastAccessed = statistics.lastAccessedAt() <= 0L
                ? "尚無存取紀錄"
                : IMAGE_DATE_FORMAT.format(Instant.ofEpochMilli(statistics.lastAccessedAt()));
        return renderTemplateString(loadTemplateResource("web/short-url-stats.html"), Map.ofEntries(
                Map.entry("__RESOURCE_NAME__", resourceName),
                Map.entry("__RESOURCE_CODE__", htmlEscape(statistics.code())),
                Map.entry("__RESOURCE_VIEWS__", String.format(Locale.ROOT, "%,d", statistics.viewCount())),
                Map.entry("__RESOURCE_CREATED__", IMAGE_DATE_FORMAT.format(
                        Instant.ofEpochMilli(statistics.createdAt()))),
                Map.entry("__RESOURCE_LAST_ACCESSED__", lastAccessed),
                Map.entry("__RESOURCE_EXPIRES__", IMAGE_DATE_FORMAT.format(
                        Instant.ofEpochMilli(statistics.expiresAt())))
        ));
    }

    private String authenticatedUserId(HttpExchange exchange) {
        try {
            String userId = authenticatedUserResolver.apply(exchange);
            return userId == null ? "" : userId.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String resolveAuthenticationHandoff(HttpExchange exchange) {
        try {
            String userId = authenticationHandoffResolver.apply(exchange);
            return userId == null ? "" : userId.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleImageShareContent(HttpExchange exchange, String code) throws IOException {
        if (!isGetOrHead(exchange)) {
            sendImageError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed");
            return;
        }
        if (code == null || code.isBlank() || code.contains("/")) {
            sendImageError(exchange, 404, "IMAGE_NOT_FOUND", "Image share not found");
            return;
        }
        ImageShare imageShare = shortUrlService.resolveImageShare(code);
        if (imageShare == null) {
            sendImageError(exchange, 404, "IMAGE_NOT_FOUND", "Image share not found");
            return;
        }
        if (imageShare.isPasswordProtected() && !hasImageAccess(exchange, imageShare)) {
            sendImageError(exchange, 403, "IMAGE_ACCESS_REQUIRED", "Image password access is required");
            return;
        }
        sendMedia(exchange, imageShare);
    }

    private void handleImageShareAccess(HttpExchange exchange, String code) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendImageError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed");
            return;
        }
        if (code == null || code.isBlank() || code.contains("/")) {
            sendImageError(exchange, 404, "IMAGE_NOT_FOUND", "Image share not found");
            return;
        }
        ImageShare imageShare = shortUrlService.resolveImageShare(code);
        if (imageShare == null) {
            sendImageError(exchange, 404, "IMAGE_NOT_FOUND", "Image share not found");
            return;
        }
        if (!imageShare.isPasswordProtected()) {
            sendJson(exchange, 200, DataObject.empty().put("ok", true).toString());
            return;
        }
        MediaPasswordAttemptGuard.Result verification = shortUrlService.verifyImageSharePasswordGuarded(
                imageShare, readPassword(exchange), clientAddress(exchange));
        if (!verification.isSuccess()) {
            sendPasswordVerificationFailure(exchange, verification);
            return;
        }
        issueImageAccess(exchange, imageShare);
        sendJson(exchange, 200, DataObject.empty().put("ok", true).toString());
    }

    private String readPassword(HttpExchange exchange) throws IOException {
        try {
            String body = new String(readBodyLimited(exchange, 64L * 1024L), StandardCharsets.UTF_8);
            Map<String, String> form = parseRequestBody(body, exchange.getRequestHeaders().getFirst("Content-Type"));
            return form.getOrDefault("password", "");
        } catch (RequestBodyTooLargeException ignored) {
            return "";
        }
    }

    private void sendImagePasswordPage(HttpExchange exchange, String code) throws IOException {
        sendHtml(exchange, 200, buildImagePasswordPage(code));
    }

    static String buildImagePasswordPage(String code) {
        return loadTemplateResource("web/image-password.html")
                .replace("__IMAGE_CODE__", htmlEscape(code));
    }

    static String buildImageViewPage(ImageShare imageShare) {
        String status = imageShare.isPasswordProtected() ? "密碼保護" : "公開分享";
        boolean video = imageShare.isVideo();
        String mediaName = video ? "影片" : "圖片";
        String contentUrl = "/api/short/image/content/" + htmlEscape(imageShare.code());
        String mediaElement = video
                ? "<video controls preload=\"metadata\" playsinline>"
                + "<source src=\"" + contentUrl + "\" type=\"" + htmlEscape(imageShare.contentType()) + "\" />"
                + "您的瀏覽器不支援影片播放。</video>"
                : "<img src=\"" + contentUrl + "\" alt=\"分享圖片 " + htmlEscape(imageShare.code()) + "\" />";
        return renderTemplateString(loadTemplateResource("web/image-view.html"), Map.ofEntries(
                Map.entry("__MEDIA_NAME__", mediaName),
                Map.entry("__MEDIA_EYEBROW__", video ? "SHARED VIDEO" : "SHARED IMAGE"),
                Map.entry("__MEDIA_ELEMENT__", mediaElement),
                Map.entry("__IMAGE_CODE__", htmlEscape(imageShare.code())),
                Map.entry("__IMAGE_STATUS__", status),
                Map.entry("__IMAGE_VIEWS__", String.format(Locale.ROOT, "%,d", imageShare.viewCount())),
                Map.entry("__IMAGE_EXPIRES__", IMAGE_DATE_FORMAT.format(Instant.ofEpochMilli(imageShare.expiresAt()))),
                Map.entry("__IMAGE_TYPE__", htmlEscape(imageShare.contentType())),
                Map.entry("__IMAGE_SIZE__", formatFileSize(imageShare.sizeBytes()))
        ));
    }

    static String buildImageExpiredPage() {
        return loadTemplateResource("web/share-expired.html");
    }

    private boolean hasImageAccess(HttpExchange exchange, ImageShare imageShare) {
        cleanupImageAccessGrants();
        String accessToken = readCookie(exchange, IMAGE_ACCESS_COOKIE);
        if (accessToken.isBlank()) {
            return false;
        }
        ImageAccessGrant grant = imageAccessGrants.get(accessToken);
        return grant != null
                && grant.code().equals(imageShare.code())
                && grant.expiresAt() > System.currentTimeMillis();
    }

    private void issueImageAccess(HttpExchange exchange, ImageShare imageShare) {
        cleanupImageAccessGrants();
        byte[] randomBytes = new byte[32];
        IMAGE_ACCESS_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        long expiresAt = Math.min(imageShare.expiresAt(), System.currentTimeMillis() + IMAGE_ACCESS_DURATION_MILLIS);
        imageAccessGrants.put(token, new ImageAccessGrant(imageShare.code(), expiresAt));
        long maxAgeSeconds = Math.max(1L, (expiresAt - System.currentTimeMillis()) / 1000L);
        String cookie = IMAGE_ACCESS_COOKIE + "=" + token + "; Max-Age=" + maxAgeSeconds + "; Path=/; HttpOnly; SameSite=Lax";
        if ("https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"))) {
            cookie += "; Secure";
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private void cleanupImageAccessGrants() {
        long now = System.currentTimeMillis();
        imageAccessGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private boolean shouldRecordView(String resourceType, String code, String clientAddress) {
        long now = System.currentTimeMillis();
        if (recentViewers.size() > 1024) {
            recentViewers.entrySet().removeIf(entry -> entry.getValue() + VIEW_DEDUPLICATION_MILLIS <= now);
        }
        String key = resourceType + ":" + code + ":" + clientAddress;
        while (true) {
            Long previous = recentViewers.putIfAbsent(key, now);
            if (previous == null) {
                return true;
            }
            if (now - previous < VIEW_DEDUPLICATION_MILLIS) {
                return false;
            }
            if (recentViewers.replace(key, previous, now)) {
                return true;
            }
        }
    }

    private String readCookie(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null || header.isBlank()) {
            return "";
        }
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && name.equals(pair[0].trim())) {
                return pair[1].trim();
            }
        }
        return "";
    }

    private AnonymousDeviceIdentityService.DeviceIdentity ensureAnonymousDevice(HttpExchange exchange) {
        AnonymousDeviceIdentityService.DeviceIdentity identity = shortUrlService.resolveAnonymousDevice(
                readCookie(exchange, ANONYMOUS_DEVICE_COOKIE), clientAddress(exchange));
        if (identity == null || !identity.newlyCreated()) {
            return identity;
        }
        String cookie = ANONYMOUS_DEVICE_COOKIE + "=" + identity.token()
                + "; Max-Age=" + shortUrlService.anonymousDeviceCookieMaxAgeSeconds()
                + "; Path=/; HttpOnly; Secure; SameSite=Lax";
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
        return identity;
    }

    private QuotaSubject resolveUploadQuotaSubject(HttpExchange exchange) {
        AnonymousDeviceIdentityService.DeviceIdentity device = ensureAnonymousDevice(exchange);
        String discordUserId;
        try {
            discordUserId = authenticatedUserResolver.apply(exchange);
        } catch (RuntimeException ignored) {
            discordUserId = "";
        }
        if (discordUserId == null || discordUserId.isBlank()) {
            return device == null ? null : device.quotaSubject();
        }
        AnonymousDeviceIdentityService.AuthenticationResult authenticated =
                shortUrlService.authenticateMediaIdentity(
                        device == null ? readCookie(exchange, ANONYMOUS_DEVICE_COOKIE) : device.token(),
                        discordUserId, clientAddress(exchange));
        return authenticated == null ? (device == null ? null : device.quotaSubject())
                : authenticated.quotaSubject();
    }

    private void sendPasswordVerificationFailure(HttpExchange exchange,
                                                 MediaPasswordAttemptGuard.Result result) throws IOException {
        int status;
        String errorCode;
        String message;
        switch (result.status()) {
            case BUSY -> {
                status = 503;
                errorCode = "PASSWORD_VERIFICATION_BUSY";
                message = "目前驗證請求較多，請稍後再試。";
            }
            case RATE_LIMITED, LOCKED -> {
                status = 429;
                errorCode = "PASSWORD_VERIFICATION_RATE_LIMITED";
                message = "密碼嘗試過於頻繁，請稍後再試。";
            }
            case INVALID_PASSWORD -> {
                status = 403;
                errorCode = "INVALID_PASSWORD";
                message = "Incorrect password";
            }
            case SUCCESS -> {
                return;
            }
            default -> throw new IllegalStateException("Unexpected password verification status");
        }
        if (result.retryAfterSeconds() > 0L) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf(result.retryAfterSeconds()));
        }
        DataObject body = DataObject.empty().put("error", message).put("errorCode", errorCode);
        if (result.retryAfterSeconds() > 0L) {
            body.put("retryAfterSeconds", result.retryAfterSeconds());
        }
        sendJson(exchange, status, body.toString());
    }

    private void sendMedia(HttpExchange exchange, ImageShare imageShare) throws IOException {
        long fileSize = imageShare.sizeBytes();
        exchange.getResponseHeaders().set("Content-Type", imageShare.contentType());
        exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + imageShare.storageName() + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(fileSize));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        ByteRange range = null;
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            range = parseByteRange(rangeHeader, fileSize);
            if (range == null) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + fileSize);
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
        }
        try (InputStream input = shortUrlService.openImageShare(imageShare)) {
            if (input == null) {
                sendHtml(exchange, 404, buildShortUrlNotFoundPage());
                return;
            }
            long start = range == null ? 0L : range.start();
            long length = range == null ? fileSize : range.length();
            if (range != null) {
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + range.start() + "-" + range.end() + "/" + fileSize);
            }
            input.skipNBytes(start);
            exchange.sendResponseHeaders(range == null ? 200 : 206, length);
            transferBytes(input, exchange, length);
            exchange.close();
        }
    }

    static ByteRange parseByteRange(String rangeHeader, long fileSize) {
        if (rangeHeader == null || fileSize <= 0L) {
            return null;
        }
        String normalizedHeader = rangeHeader.trim();
        if (!normalizedHeader.regionMatches(true, 0, "bytes=", 0, "bytes=".length())) {
            return null;
        }
        String value = normalizedHeader.substring("bytes=".length()).trim();
        if (value.isBlank() || value.contains(",")) {
            return null;
        }
        String[] bounds = value.split("-", -1);
        if (bounds.length != 2) {
            return null;
        }
        try {
            long start;
            long end;
            if (bounds[0].isBlank()) {
                long suffixLength = Long.parseLong(bounds[1]);
                if (suffixLength <= 0L) {
                    return null;
                }
                start = Math.max(0L, fileSize - suffixLength);
                end = fileSize - 1L;
            } else {
                start = Long.parseLong(bounds[0]);
                end = bounds[1].isBlank() ? fileSize - 1L : Long.parseLong(bounds[1]);
                end = Math.min(end, fileSize - 1L);
            }
            return start >= 0L && start < fileSize && end >= start
                    ? new ByteRange(start, end)
                    : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void transferBytes(InputStream input, HttpExchange exchange, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            exchange.getResponseBody().write(buffer, 0, read);
            remaining -= read;
        }
    }

    private void sendImageUploadFailure(HttpExchange exchange, ImageShareService.UploadError error) throws IOException {
        if (error == null) {
            sendImageError(exchange, 500, "IMAGE_CREATE_FAILED", "Unable to create image share");
            return;
        }
        switch (error) {
            case DISABLED -> sendImageError(exchange, 503, "IMAGE_SHARING_DISABLED", "Image sharing is disabled");
            case IMAGE_REQUIRED -> sendImageError(exchange, 400, "IMAGE_REQUIRED", "An image or video file is required");
            case UNSUPPORTED_MEDIA -> sendImageError(exchange, 400, "UNSUPPORTED_MEDIA", "Only PNG, JPEG, GIF, WebP, MP4, and WebM files are supported");
            case IMAGE_TOO_LARGE -> sendImageError(exchange, 413, "IMAGE_TOO_LARGE", "The uploaded image exceeds the configured size limit");
            case VIDEO_TOO_LARGE -> sendImageError(exchange, 413, "VIDEO_TOO_LARGE", "The uploaded video exceeds the configured size limit");
            case VIDEO_TOO_LONG -> sendImageError(exchange, 400, "VIDEO_TOO_LONG", "The uploaded video exceeds the configured duration limit");
            case RETENTION_TOO_LONG -> sendImageError(exchange, 400, "RETENTION_TOO_LONG", "The requested retention is outside the allowed range");
            case PASSWORD_REQUIRED -> sendImageError(exchange, 400, "MEDIA_PASSWORD_REQUIRED", "啟用密碼保護時必須設定密碼。");
            case INVALID_PASSWORD -> sendImageError(exchange, 400, "INVALID_PASSWORD", "Password length is outside the configured range");
            case UPLOAD_RATE_LIMITED -> sendImageError(exchange, 429, "MEDIA_UPLOAD_RATE_LIMITED", "Upload rate limit exceeded");
            case DAILY_QUOTA_EXCEEDED -> sendImageError(exchange, 429, "MEDIA_DAILY_QUOTA_EXCEEDED", "Daily upload quota exceeded");
            case ACTIVE_STORAGE_QUOTA_EXCEEDED -> sendImageError(exchange, 413, "MEDIA_ACTIVE_STORAGE_QUOTA_EXCEEDED", "Active storage quota exceeded");
            case GLOBAL_STORAGE_FULL -> sendImageError(exchange, 503, "MEDIA_MANAGED_STORAGE_FULL", "Managed media storage is full");
            case FILESYSTEM_FULL -> sendImageError(exchange, 503, "MEDIA_FILESYSTEM_FULL", "Media uploads are paused because filesystem usage is too high");
            case STORAGE_FAILED -> sendImageError(exchange, 500, "MEDIA_STORAGE_FAILED", "Unable to store the uploaded media");
            case PERSISTENCE_FAILED -> sendImageError(exchange, 500, "MEDIA_PERSISTENCE_FAILED", "Unable to save the media share record");
            case CREATE_FAILED -> sendImageError(exchange, 500, "IMAGE_CREATE_FAILED", "Unable to create image share");
        }
    }

    private void sendImageError(HttpExchange exchange, int status, String errorCode, String error) throws IOException {
        sendJson(exchange, status, DataObject.empty().put("error", error).put("errorCode", errorCode).toString());
    }

    private void handleWebAsset(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path == null || !path.startsWith("/web/") || path.contains("..")) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }
        String resourcePath = path;
        try (InputStream in = ShortUrlGatewayServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendHtml(exchange, 404, buildShortUrlNotFoundPage());
                return;
            }
            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", webAssetContentType(resourcePath));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }

    private String buildShortUrlNotFoundPage() {
        return renderTemplateString(loadTemplate("web/404.html"), Map.of(
                "__NOT_FOUND_KICKER__", "NoRule URL",
                "__NOT_FOUND_TITLE__", "短網址不存在或已失效",
                "__NOT_FOUND_DESCRIPTION__", "短網址不存在或已失效",
                "__NOT_FOUND_ACTION_URL__", "/",
                "__NOT_FOUND_ACTION_TEXT__", "Back to Short URL Home"
        ));
    }

    private boolean isGetOrHead(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private ExpirationRequest parseExpirationRequest(MultipartForm form, ImageShareService.Options options) {
        try {
            String rawExpiresAt = form.value("expiresAt");
            if (!rawExpiresAt.isBlank()) {
                long expiresAt = Long.parseLong(rawExpiresAt);
                long retention = expiresAt - System.currentTimeMillis();
                if (retention <= 0L || retention > options.maxRetentionMillis()) {
                    return null;
                }
                return new ExpirationRequest(0L, expiresAt);
            }

            String rawMinutes = form.value("retentionMinutes");
            if (!rawMinutes.isBlank()) {
                long minutes = Long.parseLong(rawMinutes);
                long retention = Math.multiplyExact(minutes, 60L * 1000L);
                return retention > 0L && retention <= options.maxRetentionMillis()
                        ? new ExpirationRequest(retention, 0L)
                        : null;
            }

            String rawHours = form.value("retentionHours");
            if (rawHours.isBlank()) {
                return new ExpirationRequest(0L, 0L);
            }
            long hours = Long.parseLong(rawHours);
            long retention = Math.multiplyExact(hours, 60L * 60L * 1000L);
            return retention > 0L && retention <= options.maxRetentionMillis()
                    ? new ExpirationRequest(retention, 0L)
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private MultipartForm parseMultipartForm(HttpExchange exchange, long maxFileSizeBytes) throws IOException, InvalidMultipartException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String boundary = extractMultipartBoundary(contentType);
        if (boundary == null) {
            throw new InvalidMultipartException();
        }
        long requestLimit;
        try {
            requestLimit = Math.addExact(maxFileSizeBytes, MULTIPART_OVERHEAD_BYTES);
        } catch (ArithmeticException e) {
            throw new RequestBodyTooLargeException();
        }
        byte[] body = readBodyLimited(exchange, requestLimit);
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] lineBreak = "\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] headersEndMarker = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] nextBoundaryMarker = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        if (!matchesAt(body, boundaryBytes, 0)) {
            throw new InvalidMultipartException();
        }

        List<MultipartPart> parts = new ArrayList<>();
        int position = 0;
        while (position < body.length) {
            if (!matchesAt(body, boundaryBytes, position)) {
                throw new InvalidMultipartException();
            }
            position += boundaryBytes.length;
            if (matchesAt(body, "--".getBytes(StandardCharsets.US_ASCII), position)) {
                return new MultipartForm(parts);
            }
            if (!matchesAt(body, lineBreak, position)) {
                throw new InvalidMultipartException();
            }
            position += lineBreak.length;

            int headersEnd = indexOf(body, headersEndMarker, position);
            if (headersEnd < 0) {
                throw new InvalidMultipartException();
            }
            String headers = new String(body, position, headersEnd - position, StandardCharsets.ISO_8859_1);
            String name = extractPartName(headers);
            if (name == null || name.isBlank()) {
                throw new InvalidMultipartException();
            }
            int contentStart = headersEnd + headersEndMarker.length;
            int nextBoundary = findNextBoundary(body, nextBoundaryMarker, contentStart);
            if (nextBoundary < 0) {
                throw new InvalidMultipartException();
            }
            parts.add(new MultipartPart(name, Arrays.copyOfRange(body, contentStart, nextBoundary)));
            position = nextBoundary + lineBreak.length;
        }
        throw new InvalidMultipartException();
    }

    private String extractMultipartBoundary(String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            return null;
        }
        Matcher matcher = MULTIPART_BOUNDARY.matcher(contentType);
        if (!matcher.find()) {
            return null;
        }
        String boundary = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        if (boundary == null || boundary.isBlank() || boundary.length() > 200 || !boundary.chars().allMatch(ch -> ch > 0x20 && ch < 0x7F)) {
            return null;
        }
        return boundary;
    }

    private String extractPartName(String headers) {
        for (String line : headers.split("\\r\\n")) {
            if (!line.regionMatches(true, 0, "Content-Disposition:", 0, "Content-Disposition:".length())) {
                continue;
            }
            Matcher matcher = CONTENT_DISPOSITION_NAME.matcher(line.substring("Content-Disposition:".length()));
            return matcher.find() ? matcher.group(1) : null;
        }
        return null;
    }

    private byte[] readBodyLimited(HttpExchange exchange, long limit) throws IOException, RequestBodyTooLargeException {
        long contentLength = parseContentLength(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (contentLength > limit) {
            throw new RequestBodyTooLargeException();
        }
        int initialCapacity = contentLength > 0L ? (int) contentLength : 8192;
        byte[] body = new byte[Math.max(1, initialCapacity)];
        byte[] chunk = new byte[8192];
        long total = 0L;
        try (InputStream input = exchange.getRequestBody()) {
            int read;
            while ((read = input.read(chunk)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > limit) {
                    throw new RequestBodyTooLargeException();
                }
                if (total > body.length) {
                    int newCapacity = body.length;
                    while (newCapacity < total) {
                        int doubled = newCapacity > Integer.MAX_VALUE / 2
                                ? Integer.MAX_VALUE
                                : newCapacity * 2;
                        newCapacity = (int) Math.min(limit, doubled);
                        if (newCapacity <= body.length) {
                            throw new RequestBodyTooLargeException();
                        }
                    }
                    body = Arrays.copyOf(body, newCapacity);
                }
                System.arraycopy(chunk, 0, body, (int) total - read, read);
            }
        }
        if (total == body.length) {
            return body;
        }
        return Arrays.copyOf(body, (int) total);
    }

    private long parseContentLength(String rawContentLength) {
        if (rawContentLength == null || rawContentLength.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(rawContentLength.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private int findNextBoundary(byte[] source, byte[] marker, int fromIndex) {
        int candidate = indexOf(source, marker, fromIndex);
        while (candidate >= 0) {
            int suffix = candidate + marker.length;
            if (matchesAt(source, "--".getBytes(StandardCharsets.US_ASCII), suffix)
                    || matchesAt(source, "\r\n".getBytes(StandardCharsets.US_ASCII), suffix)) {
                return candidate;
            }
            candidate = indexOf(source, marker, candidate + 1);
        }
        return -1;
    }

    private int indexOf(byte[] source, byte[] target, int fromIndex) {
        if (target.length == 0 || source.length < target.length) {
            return -1;
        }
        for (int index = Math.max(0, fromIndex); index <= source.length - target.length; index++) {
            if (matchesAt(source, target, index)) {
                return index;
            }
        }
        return -1;
    }

    private boolean matchesAt(byte[] source, byte[] target, int index) {
        if (index < 0 || index + target.length > source.length) {
            return false;
        }
        for (int offset = 0; offset < target.length; offset++) {
            if (source[index + offset] != target[offset]) {
                return false;
            }
        }
        return true;
    }

    private String loadTemplate(String resourcePath) {
        return loadTemplateResource(resourcePath);
    }

    private static String loadTemplateResource(String resourcePath) {
        String normalizedPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (InputStream input = ShortUrlGatewayServer.class.getResourceAsStream(normalizedPath)) {
            if (input == null) {
                throw new IllegalStateException("Missing short-url template: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load short-url template: " + resourcePath, exception);
        }
    }

    private static String renderTemplateString(String template, Map<String, String> replacements) {
        String rendered = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
    }

    private static String htmlEscape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatFileSize(long sizeBytes) {
        if (sizeBytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f MB", sizeBytes / (1024.0 * 1024.0));
        }
        if (sizeBytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", sizeBytes / 1024.0);
        }
        return Math.max(0L, sizeBytes) + " B";
    }

    private String clientAddress(HttpExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRemoteAddress();
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && isTrustedLocalProxy(remoteAddress)) {
            return forwarded.split(",", 2)[0].trim();
        }
        return remoteAddress == null || remoteAddress.getAddress() == null
                ? "unknown"
                : remoteAddress.getAddress().getHostAddress();
    }

    private boolean isTrustedLocalProxy(InetSocketAddress remoteAddress) {
        return remoteAddress != null && remoteAddress.getAddress() != null
                && (remoteAddress.getAddress().isLoopbackAddress()
                || remoteAddress.getAddress().isSiteLocalAddress());
    }

    private String userAgent(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("User-Agent");
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = urlDecode(kv[0]);
            String value = kv.length > 1 ? urlDecode(kv[1]) : "";
            if (!key.isBlank()) {
                map.put(key, value);
            }
        }
        return map;
    }

    private Map<String, String> parseRequestBody(String body, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            try {
                DataObject json = DataObject.fromJson(body == null ? "{}" : body);
                Map<String, String> map = new HashMap<>();
                map.put("url", json.getString("url", "").trim());
                map.put("customCode", json.getString("customCode", "").trim());
                map.put("code", json.getString("code", "").trim());
                map.put("slug", json.getString("slug", "").trim());
                map.put("password", json.getString("password", ""));
                return map;
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return parseUrlEncoded(body);
    }

    private String urlDecode(String value) {
        return java.net.URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String webAssetContentType(String resourcePath) {
        if (resourcePath.endsWith(".js")) {
            return "text/javascript; charset=UTF-8";
        }
        if (resourcePath.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (resourcePath.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (resourcePath.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        return "text/plain; charset=UTF-8";
    }

    private void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record MultipartPart(String name, byte[] content) {
    }

    private record ExpirationRequest(long retentionMillis, long expiresAtMillis) {
    }

    private record ImageAccessGrant(String code, long expiresAt) {
    }

    record ByteRange(long start, long end) {
        long length() {
            return end - start + 1L;
        }
    }

    private static final class MultipartForm {
        private final List<MultipartPart> parts;

        private MultipartForm(List<MultipartPart> parts) {
            this.parts = parts;
        }

        private String value(String name) {
            for (MultipartPart part : parts) {
                if (name.equals(part.name())) {
                    return new String(part.content(), StandardCharsets.UTF_8).trim();
                }
            }
            return "";
        }

        private byte[] firstFile(String name) {
            for (MultipartPart part : parts) {
                if (name.equals(part.name())) {
                    return part.content();
                }
            }
            return null;
        }
    }

    private static final class RequestBodyTooLargeException extends IOException {
    }

    private static final class InvalidMultipartException extends Exception {
    }
}
