package com.norule.musicbot.web.service;

import com.norule.musicbot.domain.shorturl.ShortUrl;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.ops.ShortUrlOps;
import com.norule.musicbot.web.security.ClientAddressResolver;
import com.sun.net.httpserver.HttpExchange;

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
        String path = exchange.getRequestURI().getPath();
        if (path.matches("/api/short/[A-Za-z0-9_-]+")
                && ("PATCH".equals(exchange.getRequestMethod()) || "DELETE".equals(exchange.getRequestMethod()))) {
            new ShortUrlManagementWebService(owner.shortUrlService())
                    .handle(exchange, owner.authenticatedUserId(exchange), path.substring("/api/short/".length()));
            return;
        }
        new ShortUrlCreationWebService(owner.shortUrlService())
                .handle(exchange, owner.authenticatedUserId(exchange), clientAddress(exchange));
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
        return ClientAddressResolver.resolve(exchange, owner.webSettings().getTrustedProxyCidrs());
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
