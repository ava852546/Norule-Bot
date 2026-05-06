package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class ShortUrlGatewayServer {
    private static final Set<String> RESERVED_PATHS = Set.of(
            "api", "assets", "static", "web", "dashboard", "short-url", "index", "404"
    );

    private final ShortUrlService shortUrlService;
    private final Supplier<BotConfig.ShortUrl> configSupplier;
    private volatile HttpServer server;
    private volatile String bindHost = "";
    private volatile int bindPort = -1;

    public ShortUrlGatewayServer(ShortUrlService shortUrlService, Supplier<BotConfig.ShortUrl> configSupplier) {
        if (shortUrlService == null) {
            throw new IllegalArgumentException("shortUrlService cannot be null");
        }
        if (configSupplier == null) {
            throw new IllegalArgumentException("configSupplier cannot be null");
        }
        this.shortUrlService = shortUrlService;
        this.configSupplier = configSupplier;
    }

    public synchronized void syncWithConfig() {
        BotConfig.ShortUrl config = config();
        if (!config.isEnabled()) {
            stop();
            return;
        }
        if (server != null && Objects.equals(bindHost, config.getBindHost()) && bindPort == config.getBindPort()) {
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
            HttpServer created = HttpServer.create(new InetSocketAddress(config.getBindHost(), config.getBindPort()), 0);
            created.createContext("/api/short", this::handleCreateShortUrl);
            created.createContext("/web/", this::handleWebAsset);
            created.createContext("/", this::handleResolve);
            created.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "NoRule-ShortUrl");
                t.setDaemon(true);
                return t;
            }));
            created.start();
            this.server = created;
            this.bindHost = config.getBindHost();
            this.bindPort = config.getBindPort();
            System.out.println("[NoRule] Short URL gateway started on http://" + config.getBindHost() + ":" + config.getBindPort());
        } catch (Exception e) {
            System.out.println("[NoRule] Failed to start short URL gateway: " + e.getMessage());
        }
    }

    private void stop() {
        HttpServer current = this.server;
        if (current == null) {
            return;
        }
        current.stop(0);
        this.server = null;
        System.out.println("[NoRule] Short URL gateway stopped.");
    }

    private void handleResolve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
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

        String target = shortUrlService.resolveTarget(code);
        if (target == null || target.isBlank()) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }
        exchange.getResponseHeaders().set("Location", target);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
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

        ShortUrlService.ShortUrlEntry created = shortUrlService.create(target, customCode);
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
                .toString());
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

    private String loadTemplate(String resourcePath) {
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

    private String renderTemplateString(String template, Map<String, String> replacements) {
        String rendered = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
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
}
