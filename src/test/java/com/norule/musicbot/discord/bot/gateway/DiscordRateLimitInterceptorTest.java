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
    @Test
    void realJdaPanelActionFinalizesTaggedBodyWithUnchangedPayload() throws Exception {
        var threads = new net.dv8tion.jda.internal.utils.config.ThreadingConfig();
        var meta = new net.dv8tion.jda.internal.utils.config.MetaConfig(2048, new java.util.concurrent.ConcurrentHashMap<>(),
                java.util.EnumSet.noneOf(net.dv8tion.jda.api.utils.cache.CacheFlag.class),
                java.util.EnumSet.noneOf(net.dv8tion.jda.internal.utils.config.flags.ConfigFlag.class));
        var jda = new net.dv8tion.jda.internal.JDAImpl(
                new net.dv8tion.jda.internal.utils.config.AuthorizationConfig("offline-test"), null, threads, meta, null, null);
        // Constructor only: no login, requester, gateway, executor or shutdown hook is started.
        {
            var channel = (net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel) Proxy.newProxyInstance(
                    RestAction.class.getClassLoader(),
                    new Class<?>[]{net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getJDA" -> jda;
                        case "getGuild" -> null;
                        case "getId" -> "123";
                        case "getIdLong" -> 123L;
                        case "getType" -> net.dv8tion.jda.api.entities.channel.ChannelType.TEXT;
                        default -> throw new AssertionError(method);
                    });
            var action = DiscordRateLimitInterceptor.panelEdit(channel, 456L, false, () -> "requestId=test");
            action.setEmbeds(new net.dv8tion.jda.api.EmbedBuilder().setDescription("latest queue").build());
            var finalizeData = action.getClass().getDeclaredMethod("finalizeData");
            finalizeData.setAccessible(true);
            var body = (RequestBody) finalizeData.invoke(action);
            assertInstanceOf(DiscordRateLimitInterceptor.TrackedBody.class, body);
            var bytes = new okio.Buffer();
            body.writeTo(bytes);
            var payload = net.dv8tion.jda.api.utils.data.DataObject.fromJson(bytes.readUtf8());
            assertEquals("latest queue", payload.getArray("embeds").getObject(0).getString("description"));
            assertFalse(payload.hasKey("requestId"));
        }
    }

    @Test
    void requestBodyProvenanceSurvives429AndCannotBeConfusedWithRouteCandidates() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DiscordRateLimitInterceptor.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var body = new DiscordRateLimitInterceptor.TrackedBody(
                    RequestBody.create(MediaType.get("application/json"), "{\"embeds\":[]}"),
                    new DiscordRateLimitInterceptor.Source("MUSIC_PANEL_REFRESH", false, false, System.nanoTime(),
                            () -> "guildId=999 refreshReason=[QUEUE_CHANGED] requestId=test-request"));
            var bytes = new okio.Buffer();
            body.writeTo(bytes);
            assertEquals("{\"embeds\":[]}", bytes.readUtf8());
            assertEquals(13, body.contentLength());
            var response = response(429, "shared", "{\"retry_after\":4}");
            var request = response.request().newBuilder().method("PATCH", body).build();
            var interceptor = new DiscordRateLimitInterceptor(queue);
            interceptor.intercept(chain(response.newBuilder().request(request).build()));
            var log = logs.list.getLast().getFormattedMessage();
            assertTrue(log.contains("feature=MUSIC_PANEL_REFRESH attribution=REQUEST_BODY_IDENTITY"));
            assertTrue(log.contains("guildId=999 refreshReason=[QUEUE_CHANGED] requestId=test-request"));
            assertFalse(log.contains("guildId=UNKNOWN"));
            // Even after a successful attempt clears the route registry, the same body retains identity.
            interceptor.intercept(chain(response.newBuilder().request(request).code(200).build()));
            new DiscordRateLimitInterceptor(queue).intercept(chain(response.newBuilder().request(request).build()));
            assertTrue(logs.list.getLast().getFormattedMessage().contains("requestAttempt=3"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void completionProvenanceUsesActionIdentityEvenWhenRoutesCollide() {
        RestAction<?> refresh = action();
        RestAction<?> delete = action();
        DiscordRateLimitInterceptor.track(refresh, "MUSIC_PANEL_REFRESH", true, false,
                "PATCH", "/channels/777/messages/888");
        DiscordRateLimitInterceptor.track(delete, "MUSIC_PANEL_DELETE", false, false,
                "PATCH", "/channels/777/messages/888");
        assertEquals("MUSIC_PANEL_DELETE", DiscordRateLimitInterceptor.completedFeature(delete));
        assertEquals("MUSIC_PANEL_REFRESH", DiscordRateLimitInterceptor.completedFeature(refresh));
        assertEquals("UNKNOWN", DiscordRateLimitInterceptor.completedFeature(refresh));
        assertEquals("UNKNOWN", DiscordRateLimitInterceptor.completedFeature(action()));
    }

    @Test
    void diagnosticSupplierFailureCannotChange429Handling() throws Exception {
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            DiscordRateLimitInterceptor.track(action(), "MUSIC_PANEL_REFRESH", true, false,
                    "PATCH", "/channels/123/messages/456", () -> { throw new IllegalStateException("unavailable"); });
            var response = response(429, "shared", "{\"retry_after\":1}");
            var interceptor = new DiscordRateLimitInterceptor(queue);
            assertSame(response, interceptor.intercept(chain(response)));
            assertEquals(1, interceptor.rateLimitedAttempts());
            interceptor.intercept(chain(response(200, "shared", "{}")));
        }
    }

    private static RestAction<?> action() {
        return (RestAction<?>) Proxy.newProxyInstance(RestAction.class.getClassLoader(),
                new Class<?>[]{RestAction.class}, (proxy, method, args) -> { throw new AssertionError(method); });
    }

    @Test
    void diagnosticObserverSeesJdaHandled429AndCannotChangeResponseOrRetries() throws Exception {
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var calls = new AtomicInteger();
            var interceptor = new DiscordRateLimitInterceptor(queue, (method, path) -> {
                assertEquals("PATCH", method);
                assertEquals("/channels/123/messages/456", path);
                calls.incrementAndGet();
                throw new IllegalStateException("Diagnostic observer unavailable");
            });
            var response = response(429, "shared", "{\"retry_after\":1}");
            assertSame(response, interceptor.intercept(chain(response)));
            assertEquals(1, calls.get());
            interceptor.intercept(chain(response(200, "shared", "{}")));
            assertEquals(1, calls.get());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"global", "shared", "user"})
    void onlyGlobal429PausesUnrelatedMaintenanceWithoutRetryingOrConsumingBody(String scope) throws Exception {
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
            assertEquals(scope.equals("global") ? 0 : 1, starts.get());
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
            assertTrue(first.contains("candidateFeature=MUSIC_PANEL_REFRESH candidatePriority=BACKGROUND candidateStartup=false observedRouteAttempt=1"));
            for (int i = 0; i < 500; i++) interceptor.intercept(chain(response));
            assertEquals(1, logs.list.size());
            new DiscordRateLimitInterceptor(queue).intercept(chain(response));
            assertTrue(logs.list.getLast().getFormattedMessage().contains("observedRouteAttempt=502"));
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

    @Test
    void retryMetadataUsesSecondsAndLargestReportedDelayWithoutConsumingBody() throws Exception {
        var response = response(429, "shared", "{\"retry_after\":3.125}").newBuilder()
                .header("Retry-After", "1").header("X-RateLimit-Reset-After", "5").build();
        assertEquals(5_000, DiscordRateLimitInterceptor.retryAfterMillis(response));
        assertEquals("{\"retry_after\":3.125}", response.body().string());
        assertEquals(3_000, DiscordRateLimitInterceptor.retryAfterMillis(
                response(429, "shared", "{}").newBuilder().header("Retry-After", "3").build()));
        assertEquals(5_000, DiscordRateLimitInterceptor.retryAfterMillis(
                response(429, "shared", "{\"retry_after\":5}").newBuilder().header("Retry-After", "1").build()));
        assertEquals(1, DiscordRateLimitInterceptor.retryAfterMillis(response(429, "shared", "{\"retry_after\":0.0001}")));
        assertEquals(-1, DiscordRateLimitInterceptor.retryAfterMillis(response(429, "shared", "invalid")));
    }

    @Test
    void warningSuppressionDoesNotHideAnotherChannelAndNeverClaimsCertainRouteAttribution() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DiscordRateLimitInterceptor.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var interceptor = new DiscordRateLimitInterceptor(queue);
            var first = response(429, "shared", "{\"retry_after\":5}");
            interceptor.intercept(chain(first));
            interceptor.intercept(chain(first));
            var secondRequest = first.request().newBuilder()
                    .url("https://discord.com/api/v10/channels/999/messages/888").build();
            interceptor.intercept(chain(first.newBuilder().request(secondRequest).build()));
            assertEquals(2, logs.list.size());
            assertTrue(logs.list.getLast().getFormattedMessage().contains("channelId=999 messageId=888"));
            assertTrue(logs.list.stream().allMatch(log -> log.getFormattedMessage().contains("feature=UNKNOWN")));
            assertTrue(logs.list.stream().noneMatch(log -> log.getFormattedMessage().contains("automaticRetry=")));
            interceptor.intercept(chain(response(200, "shared", "{}")));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
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
