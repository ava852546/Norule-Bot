package com.norule.musicbot.web.service;

import com.norule.musicbot.domain.shorturl.ShortUrl;
import com.norule.musicbot.domain.shorturl.ShortUrlCreationError;
import com.norule.musicbot.service.shorturl.ShortUrlCreationGuard;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.ops.ShortUrlOps;
import com.norule.musicbot.web.security.ClientAddressResolver;
import com.norule.musicbot.web.security.HttpRequestBodyReader;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ShortUrlWebService {
    private static final Set<String> RESERVED_PATHS = Set.of(
            "api", "assets", "static", "web", "dashboard", "short-url", "index", "404"
    );

    private final WebControlServer owner;
    private final ShortUrlOps shortUrlOps;

    public ShortUrlWebService(WebControlServer owner) {
        this.owner = owner;
        this.shortUrlOps = new ShortUrlOps(new com.norule.musicbot.service.shorturl.ShortUrlService(owner.shortUrlService()));
    }

    public void handleCreateShortUrl(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendJson(exchange, 405, DataObject.empty()
                    .put("error", "Method Not Allowed")
                    .put("errorCode", "METHOD_NOT_ALLOWED"));
            return;
        }

        String body;
        try {
            body = owner.readBody(exchange, HttpRequestBodyReader.MAX_SHORT_URL_REQUEST_BODY_BYTES);
        } catch (HttpRequestBodyReader.RequestBodyTooLargeException ignored) {
            owner.sendJson(exchange, 413, DataObject.empty()
                    .put("error", "Request body too large")
                    .put("errorCode", "REQUEST_BODY_TOO_LARGE"));
            return;
        }
        String ownerUserId = owner.authenticatedUserId(exchange);
        String address = clientAddress(exchange);
        ShortUrlCreationGuard.Decision requestDecision = owner.shortUrlService()
                .checkCreationRequest(ownerUserId, address);
        if (!requestDecision.allowed()) {
            sendCreationGuardFailure(exchange, requestDecision.status(), requestDecision.retryAfterSeconds());
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        Map<String, String> form = parseRequestBody(body, contentType);
        String target = form.getOrDefault("url", "").trim();
        String customCode = form.getOrDefault("customCode", form.getOrDefault("code", form.getOrDefault("slug", ""))).trim();
        if (target.isBlank()) {
            owner.sendJson(exchange, 400, DataObject.empty()
                    .put("error", "Missing url")
                    .put("errorCode", "MISSING_URL"));
            return;
        }

        try (ShortUrlCreationGuard.CreationPermit permit = owner.shortUrlService()
                .beginShortUrlCreation(ownerUserId, address)) {
            if (!permit.allowed()) {
                sendCreationGuardFailure(exchange, permit.status(), permit.retryAfterSeconds());
                return;
            }
            ShortUrlOps.CreationResult result = shortUrlOps.createFromWebWithOutcome(
                    target, customCode, ownerUserId, address);
            ShortUrl created = result.shortUrl();
            if (created == null) {
                sendCreationFailure(exchange, result.error());
                return;
            }
            if (result.newlyCreated()) {
                permit.commitSuccessfulCreation();
            }

            owner.sendJson(exchange, 200, DataObject.empty()
                    .put("code", created.code())
                    .put("shortUrl", owner.shortUrlService().toPublicUrl(created.code()))
                    .put("targetUrl", created.target())
                    .put("viewCount", created.viewCount()));
        }
    }

    public void handleResolveShortUrl(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            sendHtml(exchange, 200, loadTemplate("web/short-url.html"));
            return;
        }

        String code = extractCode(path);
        if (code == null) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }

        ShortUrl resolved = shortUrlOps.resolve(code);
        if (resolved == null || resolved.target() == null || resolved.target().isBlank()) {
            sendHtml(exchange, 404, buildShortUrlNotFoundPage());
            return;
        }

        if ("GET".equalsIgnoreCase(method)) {
            shortUrlOps.recordView(code, clientAddress(exchange), userAgent(exchange));
        }

        owner.redirect(exchange, resolved.target());
    }

    private String extractCode(String path) {
        if (!path.startsWith("/")) {
            return null;
        }
        String value = path.substring(1).trim();
        if (value.isBlank() || value.contains("/") || RESERVED_PATHS.contains(value.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return value;
    }

    private String loadTemplate(String resourcePath) {
        String normalizedPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (InputStream input = WebControlServer.class.getResourceAsStream(normalizedPath)) {
            if (input == null) {
                throw new IllegalStateException("Missing web template: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load web template: " + resourcePath, exception);
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

    private Map<String, String> parseRequestBody(String body, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            try {
                DataObject json = DataObject.fromJson(body == null ? "{}" : body);
                return Map.of(
                        "url", json.getString("url", "").trim(),
                        "customCode", json.getString("customCode", "").trim(),
                        "code", json.getString("code", "").trim(),
                        "slug", json.getString("slug", "").trim()
                );
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return owner.parseUrlEncoded(body);
    }

    private String renderTemplateString(String template, Map<String, String> replacements) {
        String rendered = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
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

    private String clientAddress(HttpExchange exchange) {
        return ClientAddressResolver.resolve(exchange);
    }

    private void sendCreationGuardFailure(HttpExchange exchange,
                                          ShortUrlCreationGuard.Status status,
                                          long retryAfterSeconds) throws IOException {
        boolean dailyQuota = status == ShortUrlCreationGuard.Status.DAILY_QUOTA_EXCEEDED;
        exchange.getResponseHeaders().set("Retry-After", String.valueOf(Math.max(1L, retryAfterSeconds)));
        owner.sendJson(exchange, 429, DataObject.empty()
                .put("error", dailyQuota
                        ? "Daily short URL creation quota exceeded"
                        : "Too many short URL requests")
                .put("errorCode", dailyQuota
                        ? "SHORT_URL_DAILY_QUOTA_EXCEEDED"
                        : "SHORT_URL_RATE_LIMITED")
                .put("retryAfterSeconds", Math.max(1L, retryAfterSeconds)));
    }

    private void sendCreationFailure(HttpExchange exchange,
                                     ShortUrlCreationError error) throws IOException {
        ShortUrlCreationError resolved = error == null ? ShortUrlCreationError.INVALID_TARGET : error;
        int status = resolved == ShortUrlCreationError.CUSTOM_CODE_ALREADY_EXISTS ? 409 : 400;
        String errorCode = switch (resolved) {
            case INVALID_CUSTOM_CODE -> "INVALID_CUSTOM_CODE";
            case RESERVED_CUSTOM_CODE -> "RESERVED_CUSTOM_CODE";
            case CUSTOM_CODE_ALREADY_EXISTS -> "CUSTOM_CODE_ALREADY_EXISTS";
            case NONE, INVALID_TARGET -> "INVALID_URL_OR_CODE";
        };
        String message = switch (resolved) {
            case INVALID_CUSTOM_CODE -> "Custom code must be 3-32 characters using only a-z, 0-9, - and _";
            case RESERVED_CUSTOM_CODE -> "This custom code is reserved by the system";
            case CUSTOM_CODE_ALREADY_EXISTS -> "This custom code is already in use";
            case NONE, INVALID_TARGET -> "Invalid URL or custom code";
        };
        owner.sendJson(exchange, status, DataObject.empty()
                .put("error", message)
                .put("errorCode", errorCode));
    }

    private String userAgent(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("User-Agent");
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }
}
