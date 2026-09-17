package com.norule.musicbot.web.service;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.service.shorturl.RateLimitService;
import com.norule.musicbot.service.shorturl.ShortUrlCreationGuard;
import com.norule.musicbot.service.shorturl.TurnstileVerifier;
import com.norule.musicbot.web.security.HttpRequestBodyReader;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Shared HTTP policy for the public and dashboard creation endpoints. */
public final class ShortUrlCreationWebService {
    private static final Logger LOG = LoggerFactory.getLogger(ShortUrlCreationWebService.class);
    private final ShortUrlService service;

    public ShortUrlCreationWebService(ShortUrlService service) {
        this.service = service;
    }

    public void handle(HttpExchange exchange, String userId, String address) throws IOException {
        if (!"/api/short".equals(exchange.getRequestURI().getPath())) {
            sendError(exchange, 404, "NOT_FOUND", "Not Found");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed");
            return;
        }
        RateLimitService.Result rate = service.checkShortUrlRate(address, userId);
        if (!rate.allowed()) {
            sendRateLimited(exchange, rate.retryAfterSeconds());
            return;
        }
        ShortUrlCreationGuard.Decision legacy = service.checkCreationRequest(userId, address);
        if (!legacy.allowed()) {
            sendRateLimited(exchange, legacy.retryAfterSeconds());
            return;
        }
        try (RateLimitService.UploadPermit concurrency = service.beginShortUrlRequest(address, userId)) {
            if (!concurrency.allowed()) {
                sendRateLimited(exchange, concurrency.retryAfterSeconds());
                return;
            }
            create(exchange, userId, address);
        }
    }

    private void create(HttpExchange exchange, String userId, String address) throws IOException {
        Map<String, String> form;
        try {
            String body = HttpRequestBodyReader.readUtf8BodyLimited(
                    exchange, HttpRequestBodyReader.MAX_SHORT_URL_REQUEST_BODY_BYTES);
            form = parseBody(body, exchange.getRequestHeaders().getFirst("Content-Type"));
        } catch (HttpRequestBodyReader.RequestBodyTooLargeException ignored) {
            sendError(exchange, 413, "REQUEST_BODY_TOO_LARGE", "Request body too large");
            return;
        } catch (IllegalArgumentException ignored) {
            sendError(exchange, 400, "INVALID_REQUEST", "Invalid request body");
            return;
        }
        String customCode = firstNonBlank(form, "customCode", "code", "slug");
        if ((userId == null || userId.isBlank()) && !customCode.isBlank()) {
            sendError(exchange, 403, "CUSTOM_CODE_AUTH_REQUIRED", "Sign in to choose a custom code");
            return;
        }
        if (userId == null || userId.isBlank()) {
            TurnstileVerifier.Result verification = service.turnstileVerifier()
                    .verify(form.getOrDefault("turnstileToken", ""), address);
            if (verification != TurnstileVerifier.Result.ALLOWED) {
                boolean unavailable = verification == TurnstileVerifier.Result.UNAVAILABLE;
                sendError(exchange, unavailable ? 503 : 403,
                        unavailable ? "TURNSTILE_UNAVAILABLE" : "TURNSTILE_REQUIRED",
                        "Please complete verification and try again");
                return;
            }
        }
        String target = form.getOrDefault("url", "").trim();
        if (target.isBlank()) {
            sendError(exchange, 400, "MISSING_URL", "Missing url");
            return;
        }
        try (ShortUrlCreationGuard.CreationPermit permit = service.beginShortUrlCreation(userId, address)) {
            if (!permit.allowed()) {
                sendRateLimited(exchange, permit.retryAfterSeconds());
                return;
            }
            // Ownership always comes from the validated session; body owner fields are ignored.
            ShortUrlService.CreationOutcome outcome = service.createFromWebWithOutcome(target, customCode, userId, address);
            if (outcome.entry() == null) {
                int status = outcome.error() == com.norule.musicbot.domain.shorturl.ShortUrlCreationError.CUSTOM_CODE_ALREADY_EXISTS
                        ? 409 : 400;
                String errorCode = switch (outcome.error()) {
                    case NONE, INVALID_TARGET -> "INVALID_URL_OR_CODE";
                    default -> outcome.error().name();
                };
                sendError(exchange, status, errorCode, "Invalid URL or custom code");
                return;
            }
            if (outcome.newlyCreated()) permit.commitSuccessfulCreation();
            ShortUrlService.ShortUrlEntry created = outcome.entry();
            LOG.info("Short URL created: actor={} userId={} code={}",
                    userId == null || userId.isBlank() ? "anonymous" : "authenticated",
                    userId == null ? "" : userId, created.code());
            sendJson(exchange, 200, DataObject.empty()
                    .put("code", created.code())
                    .put("shortUrl", service.toPublicUrl(created.code()))
                    .put("targetUrl", created.target())
                    .put("viewCount", created.viewCount()));
        }
    }

    static Map<String, String> parseBody(String body, String contentType) {
        Map<String, String> result = new HashMap<>();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            try {
                DataObject json = DataObject.fromJson(body);
                for (String key : new String[]{"url", "customCode", "code", "slug", "turnstileToken"}) {
                    result.put(key, json.getString(key, "").trim());
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid JSON body", exception);
            }
        } else {
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2) {
                    result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    private static String firstNonBlank(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.getOrDefault(key, "").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    public static void sendRateLimited(HttpExchange exchange, long seconds) throws IOException {
        long retryAfter = Math.max(1L, seconds);
        exchange.getResponseHeaders().set("Retry-After", Long.toString(retryAfter));
        sendJson(exchange, 429, DataObject.empty()
                .put("error", "RATE_LIMITED").put("errorCode", "RATE_LIMITED")
                .put("message", "Too many requests.")
                .put("retryAfter", retryAfter).put("retryAfterSeconds", retryAfter));
    }

    public static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        sendJson(exchange, status, DataObject.empty().put("error", code)
                .put("errorCode", code).put("message", message));
    }

    public static void sendJson(HttpExchange exchange, int status, DataObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
