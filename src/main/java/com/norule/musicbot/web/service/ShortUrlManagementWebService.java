package com.norule.musicbot.web.service;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.service.shorturl.RateLimitService;
import com.norule.musicbot.web.security.HttpRequestBodyReader;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Locale;

public final class ShortUrlManagementWebService {
    private final ShortUrlService service;

    public ShortUrlManagementWebService(ShortUrlService service) { this.service = service; }

    public void handle(HttpExchange exchange, String userId, String code) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"PATCH".equals(method) && !"DELETE".equals(method)) {
            ShortUrlCreationWebService.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed");
            return;
        }
        if (userId == null || userId.isBlank()) {
            ShortUrlCreationWebService.sendError(exchange, 401, "UNAUTHORIZED", "Sign in to manage URLs");
            return;
        }
        RateLimitService.Result rate = service.checkShortUrlApiRate(userId);
        if (!rate.allowed()) {
            ShortUrlCreationWebService.sendRateLimited(exchange, rate.retryAfterSeconds());
            return;
        }
        String target = "";
        if ("PATCH".equals(method)) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                ShortUrlCreationWebService.sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "JSON body required");
                return;
            }
            try {
                String body = HttpRequestBodyReader.readUtf8BodyLimited(
                        exchange, HttpRequestBodyReader.MAX_SHORT_URL_REQUEST_BODY_BYTES);
                target = ShortUrlCreationWebService.parseBody(body, contentType).getOrDefault("url", "");
            } catch (HttpRequestBodyReader.RequestBodyTooLargeException ignored) {
                ShortUrlCreationWebService.sendError(exchange, 413, "REQUEST_BODY_TOO_LARGE", "Request body too large");
                return;
            } catch (IllegalArgumentException ignored) {
                ShortUrlCreationWebService.sendError(exchange, 400, "INVALID_REQUEST", "Invalid JSON body");
                return;
            }
        }
        ShortUrlService.MutationResult result = service.mutateOwned(code, userId, target, "DELETE".equals(method));
        int status = switch (result) {
            case SUCCESS -> 200;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case INVALID_TARGET -> 400;
        };
        if (result == ShortUrlService.MutationResult.SUCCESS) {
            ShortUrlCreationWebService.sendJson(exchange, status, DataObject.empty().put("success", true));
        } else {
            ShortUrlCreationWebService.sendError(exchange, status, result.name(), "Unable to manage this URL");
        }
    }
}
