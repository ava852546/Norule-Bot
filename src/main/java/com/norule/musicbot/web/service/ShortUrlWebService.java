package com.norule.musicbot.web.service;

import com.norule.musicbot.domain.shorturl.ShortUrl;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.ops.ShortUrlOps;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ShortUrlWebService {
    private static final String PUBLIC_BASE_URL = "https://s.norule.me";

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

        String body = owner.readBody(exchange);
        Map<String, String> form = owner.parseUrlEncoded(body);
        String target = form.getOrDefault("url", "").trim();
        String customCode = form.getOrDefault("code", form.getOrDefault("slug", "")).trim();
        if (target.isBlank()) {
            owner.sendJson(exchange, 400, DataObject.empty()
                    .put("error", "Missing url")
                    .put("errorCode", "MISSING_URL"));
            return;
        }

        if (!customCode.isBlank()) {
            ShortUrl existingByTarget = shortUrlOps.findActiveByTarget(target);
            if (existingByTarget != null) {
                owner.sendJson(exchange, 409, DataObject.empty()
                        .put("error", "Target already shortened")
                        .put("errorCode", "TARGET_ALREADY_SHORTENED")
                        .put("url", buildPublicUrl(existingByTarget.code()))
                        .put("code", existingByTarget.code())
                        .put("target", existingByTarget.target()));
                return;
            }
        }

        ShortUrl created = shortUrlOps.create(target, customCode);
        if (created == null) {
            owner.sendJson(exchange, 400, DataObject.empty()
                    .put("error", "Invalid url or code")
                    .put("errorCode", "INVALID_URL_OR_CODE"));
            return;
        }

        owner.sendJson(exchange, 200, DataObject.empty()
                .put("ok", true)
                .put("code", created.code())
                .put("url", buildPublicUrl(created.code()))
                .put("target", created.target())
                .put("createdAt", created.createdAt())
                .put("expiresAt", created.expiresAt()));
    }

    public void handleResolveShortUrl(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            sendHtml(exchange, 200, loadTemplate("web/index.html"));
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

        owner.redirect(exchange, resolved.target());
    }

    private String buildPublicUrl(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return PUBLIC_BASE_URL + "/" + code.trim();
    }

    private String extractCode(String path) {
        if (!path.startsWith("/")) {
            return null;
        }
        String value = path.substring(1).trim();
        if (value.isBlank() || value.contains("/")) {
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
                "__NOT_FOUND_TITLE__", "Short URL Not Found",
                "__NOT_FOUND_DESCRIPTION__", "This short link does not exist, has expired, or was removed.",
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
}
