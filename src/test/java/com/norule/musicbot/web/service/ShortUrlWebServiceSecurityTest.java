package com.norule.musicbot.web.service;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.shorturl.InMemoryRateLimitStore;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import com.norule.musicbot.service.shorturl.RateLimitService;
import com.norule.musicbot.service.shorturl.ShortUrlCreationGuard;
import com.norule.musicbot.web.TestHttpExchange;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.infra.WebSettings;
import com.norule.musicbot.web.security.HttpRequestBodyReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlWebServiceSecurityTest {
    @Test
    void acceptsNormalJsonAndFormRequests() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        WebControlServer owner = newOwner(repository);
        try {
            TestHttpExchange json = new TestHttpExchange(
                    "POST",
                    "/api/short",
                    "{\"url\":\"https://example.com/json\",\"customCode\":\"json-code\"}"
                            .getBytes(StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json");
            TestHttpExchange form = new TestHttpExchange(
                    "POST",
                    "/api/short",
                    "url=https%3A%2F%2Fexample.com%2Fform&slug=form-code"
                            .getBytes(StandardCharsets.UTF_8))
                    .header("Content-Type", "application/x-www-form-urlencoded");

            ShortUrlWebService service = new ShortUrlWebService(owner);
            service.handleCreateShortUrl(json);
            service.handleCreateShortUrl(form);

            assertEquals(200, json.responseCode());
            assertEquals(200, form.responseCode());
            assertEquals(2, repository.entries.size());
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void returnsSpecificCustomCodeErrorsAndNormalizesSuccessfulCodes() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        WebControlServer owner = newOwner(repository);
        try {
            ShortUrlWebService service = new ShortUrlWebService(owner);
            TestHttpExchange invalid = jsonRequest(
                    "{\"url\":\"https://example.com/invalid\",\"customCode\":\"bad/code\"}");
            TestHttpExchange reserved = jsonRequest(
                    "{\"url\":\"https://example.com/reserved\",\"customCode\":\"ADMIN\"}");
            TestHttpExchange created = jsonRequest(
                    "{\"url\":\"https://example.com/created\",\"customCode\":\"My-Code\"}");
            TestHttpExchange duplicate = jsonRequest(
                    "{\"url\":\"https://example.com/duplicate\",\"customCode\":\"MY-CODE\"}");

            service.handleCreateShortUrl(invalid);
            service.handleCreateShortUrl(reserved);
            service.handleCreateShortUrl(created);
            service.handleCreateShortUrl(duplicate);

            assertEquals(400, invalid.responseCode());
            assertTrue(invalid.responseBodyUtf8().contains("INVALID_CUSTOM_CODE"));
            assertEquals(400, reserved.responseCode());
            assertTrue(reserved.responseBodyUtf8().contains("RESERVED_CUSTOM_CODE"));
            assertEquals(200, created.responseCode());
            assertTrue(created.responseBodyUtf8().contains("\"code\":\"my-code\""));
            assertEquals(409, duplicate.responseCode());
            assertTrue(duplicate.responseBodyUtf8().contains("CUSTOM_CODE_ALREADY_EXISTS"));
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void rejectsChunkedBodyLargerThanSixteenKibibytes() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        WebControlServer owner = newOwner(repository);
        try {
            byte[] oversized = new byte[(int) HttpRequestBodyReader.MAX_SHORT_URL_REQUEST_BODY_BYTES + 1];
            TestHttpExchange exchange = new TestHttpExchange("POST", "/api/short", oversized)
                    .header("Content-Type", "application/json");

            new ShortUrlWebService(owner).handleCreateShortUrl(exchange);

            assertEquals(413, exchange.responseCode());
            assertTrue(exchange.responseBodyUtf8().contains("REQUEST_BODY_TOO_LARGE"));
            assertTrue(repository.entries.isEmpty());
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void invalidRequestsConsumeRequestRateButNotSuccessfulCreationQuota() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        WebControlServer owner = newOwner(repository);
        owner.shortUrlService().updateCreationGuardOptions(
                new ShortUrlCreationGuard.Options(true, 100, 100, 1, 100, 100, 1));
        try {
            TestHttpExchange invalid = jsonRequest("{\"url\":\"not-a-url\"}");
            TestHttpExchange firstValid = jsonRequest("{\"url\":\"https://example.com/one\"}");
            TestHttpExchange dailyDenied = jsonRequest("{\"url\":\"https://example.com/two\"}");

            ShortUrlWebService service = new ShortUrlWebService(owner);
            service.handleCreateShortUrl(invalid);
            service.handleCreateShortUrl(firstValid);
            service.handleCreateShortUrl(dailyDenied);

            assertEquals(400, invalid.responseCode());
            assertEquals(200, firstValid.responseCode());
            assertEquals(429, dailyDenied.responseCode());
            assertTrue(dailyDenied.responseBodyUtf8().contains("SHORT_URL_DAILY_QUOTA_EXCEEDED"));
            assertTrue(Long.parseLong(dailyDenied.getResponseHeaders().getFirst("Retry-After")) > 0L);
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void returnsRateLimitResponseAfterAnonymousRequestLimit() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        WebControlServer owner = newOwner(repository);
        owner.shortUrlService().updateCreationGuardOptions(
                new ShortUrlCreationGuard.Options(true, 1, 1, 100, 1, 1, 100));
        try {
            ShortUrlWebService service = new ShortUrlWebService(owner);
            TestHttpExchange invalid = jsonRequest("{\"url\":\"not-a-url\"}");
            TestHttpExchange denied = jsonRequest("{\"url\":\"https://example.com\"}");

            service.handleCreateShortUrl(invalid);
            service.handleCreateShortUrl(denied);

            assertEquals(400, invalid.responseCode());
            assertEquals(429, denied.responseCode());
            assertTrue(denied.responseBodyUtf8().contains("SHORT_URL_RATE_LIMITED"));
            assertTrue(Long.parseLong(denied.getResponseHeaders().getFirst("Retry-After")) > 0L);
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void apiRateLimitRunsBeforeRequestBodyIsReadAndUsesUnifiedResponse() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        RateLimitService rateLimitService = new RateLimitService(
                new InMemoryRateLimitStore(),
                new RateLimitService.Options(true, 100, 100, 200, 1, 100, 2, 3));
        ShortUrlService shortUrlService = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 60_000L, 60_000L,
                        "https://s.norule.me", 7, false),
                null,
                null,
                rateLimitService);
        WebControlServer owner = newOwner(repository, shortUrlService);
        try {
            ShortUrlWebService service = new ShortUrlWebService(owner);
            TestHttpExchange first = jsonRequest("{\"url\":\"not-a-url\"}");
            TestHttpExchange deniedBeforeBody = jsonRequest("{}").header(
                    "Content-Length",
                    String.valueOf(HttpRequestBodyReader.MAX_SHORT_URL_REQUEST_BODY_BYTES + 1L));

            service.handleCreateShortUrl(first);
            service.handleCreateShortUrl(deniedBeforeBody);

            assertEquals(400, first.responseCode());
            assertEquals(429, deniedBeforeBody.responseCode());
            assertTrue(deniedBeforeBody.responseBodyUtf8().contains("\"error\":\"RATE_LIMITED\""));
            assertTrue(deniedBeforeBody.responseBodyUtf8().contains("\"retryAfter\":"));
            assertTrue(Long.parseLong(
                    deniedBeforeBody.getResponseHeaders().getFirst("Retry-After")) > 0L);
            assertTrue(repository.entries.isEmpty());
        } finally {
            owner.shutdown();
        }
    }

    private TestHttpExchange jsonRequest(String json) {
        return new TestHttpExchange("POST", "/api/short", json.getBytes(StandardCharsets.UTF_8))
                .header("Content-Type", "application/json");
    }

    private WebControlServer newOwner(InMemoryRepository repository) {
        return newOwner(repository, new ShortUrlService(repository));
    }

    private WebControlServer newOwner(InMemoryRepository repository, ShortUrlService shortUrlService) {
        return new WebControlServer(
                null,
                null,
                null,
                null,
                null,
                shortUrlService,
                () -> new WebSettings(false, 60_000, "https://dash.example.com", 60, "", "", ""),
                null,
                () -> "lang",
                null
        );
    }

    private static final class InMemoryRepository implements ShortUrlRepository {
        private final Map<String, ShortUrlService.ShortUrlEntry> entries = new LinkedHashMap<>();

        @Override
        public ShortUrlService.ShortUrlEntry findByCode(String code) {
            return entries.get(code);
        }

        @Override
        public ShortUrlService.ShortUrlEntry findByCodeIgnoreCase(String code) {
            return entries.values().stream()
                    .filter(entry -> entry.code().equalsIgnoreCase(code))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public ShortUrlService.ShortUrlEntry findActiveByTarget(String target, long nowMillis) {
            return entries.values().stream()
                    .filter(entry -> entry.target().equals(target) && entry.expiresAt() > nowMillis)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void save(ShortUrlService.ShortUrlEntry entry) { entries.put(entry.code(), entry); }

        @Override
        public void deleteByCode(String code) { entries.remove(code); }

        @Override
        public int cleanupExpired(long nowMillis) { return 0; }

        @Override
        public long incrementViewCount(String code) { return 0L; }

        @Override
        public Long findLogChannelId() { return null; }

        @Override
        public void saveLogChannelId(Long channelId) { }
    }
}
