package com.norule.musicbot.discord.bot.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.dv8tion.jda.api.requests.RestAction;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiscordRateLimitInterceptorTest {
    @ParameterizedTest
    @ValueSource(strings = {"global", "shared", "user"})
    void actual429PausesNewMaintenanceAndResumesWithoutRetryingOrConsumingBody(String scope) throws Exception {
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var interceptor = new DiscordRateLimitInterceptor(queue);
            String json = "{\"retry_after\":2.125,\"global\":" + scope.equals("global") + "}";
            var response = response(429, scope, json);
            var observed = interceptor.intercept(chain(response));
            assertSame(response, observed);
            assertEquals(2125, DiscordRateLimitInterceptor.retryAfterMillis(observed));
            assertEquals(json, observed.body().string());
            var starts = new AtomicInteger();
            queue.submit("after429", () -> {
                starts.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            clock.advance(2124);
            assertEquals(0, starts.get());
            clock.advance(1);
            assertEquals(1, starts.get());
            clock.advance(60_000);
            assertEquals(1, starts.get());
        }
    }

    @Test
    void fractionalHeadersRoundUpAndMalformedMetadataKeepsConservativePause() throws Exception {
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var malformed = response(429, "global", "not json").newBuilder()
                    .header("Retry-After", "invalid").header("X-RateLimit-Reset-After", "0.0001").build();
            assertEquals(1, DiscordRateLimitInterceptor.retryAfterMillis(malformed));
            new DiscordRateLimitInterceptor(queue).intercept(chain(malformed));
            var starts = new AtomicInteger();
            queue.submit("malformed", () -> {
                starts.incrementAndGet(); return CompletableFuture.completedFuture(null);
            });
            clock.advance(999);
            assertEquals(0, starts.get());
            clock.advance(1);
            assertEquals(1, starts.get());
        }
    }

    @Test
    void logsExplicitProvenanceAndRetryWithoutWarningStorm() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DiscordRateLimitInterceptor.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var response = response(429, "global", "{\"retry_after\":1}");
            RestAction<?> action = (RestAction<?>) Proxy.newProxyInstance(RestAction.class.getClassLoader(),
                    new Class<?>[]{RestAction.class}, (proxy, method, args) -> { throw new AssertionError(method); });
            DiscordRateLimitInterceptor.track(action, "MUSIC_PANEL_REFRESH", true, false,
                    "PATCH", "/channels/123/messages/456");
            var interceptor = new DiscordRateLimitInterceptor(queue);
            interceptor.intercept(chain(response));
            String first = logs.list.getFirst().getFormattedMessage();
            assertTrue(first.contains("method=PATCH route=/channels/{id}/messages/{id}"));
            assertTrue(first.contains("feature=MUSIC_PANEL_REFRESH priority=BACKGROUND startup=false automaticRetry=false"));
            for (int i = 0; i < 500; i++) interceptor.intercept(chain(response));
            assertEquals(1, logs.list.size());
            new DiscordRateLimitInterceptor(queue).intercept(chain(response));
            assertTrue(logs.list.getLast().getFormattedMessage().contains("automaticRetry=true"));
            // A successful response ends attribution for that logical operation.
            interceptor.intercept(chain(response(200, "global", "{}")));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void routeTemplateCannotExposeWebhookOrInteractionTokens() {
        assertEquals("/webhooks/{id}/{id}/messages/@original",
                DiscordRateLimitInterceptor.routeTemplate("/webhooks/123/secret-token/messages/@original"));
    }

    private static Response response(int code, String scope, String json) {
        var request = new Request.Builder().url("https://discord.com/api/v10/channels/123/messages/456")
                .method("PATCH", RequestBody.create(MediaType.get("application/json"), "{}")).build();
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("simulated")
                .header("X-RateLimit-Scope", scope)
                .body(ResponseBody.create(MediaType.get("application/json"), json)).build();
    }

    private static Interceptor.Chain chain(Response response) {
        return (Interceptor.Chain) Proxy.newProxyInstance(Interceptor.Chain.class.getClassLoader(),
                new Class<?>[]{Interceptor.Chain.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "request" -> response.request();
                    case "proceed" -> response;
                    default -> throw new AssertionError("Unexpected network access: " + method);
                });
    }
}
