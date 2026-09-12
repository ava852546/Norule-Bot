package com.norule.musicbot.discord.bot.gateway.wordchain;

import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreeDictionaryApiGatewayTest {
    private static final String FOUND_BODY = "[{\"word\":\"apple\",\"meanings\":[]}]";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private final AtomicInteger calls = new AtomicInteger();
    private final Queue<ResponseSpec> responses = new ConcurrentLinkedQueue<>();
    private volatile ResponseSpec defaultResponse;
    private volatile String observedUserAgent;

    @BeforeEach
    void startServer() throws IOException {
        defaultResponse = response(200, "[]");
        observedUserAgent = "";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/dictionary/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void objectEntryArrayReturnsFoundAndSendsUserAgent() {
        defaultResponse = response(200, FOUND_BODY);

        assertEquals(DictionaryLookupResult.FOUND, gateway().lookup("apple").join());
        assertEquals("NoRule-Bot/1.0", observedUserAgent);
    }

    @Test
    void emptyArrayReturnsNotFound() {
        assertEquals(DictionaryLookupResult.NOT_FOUND, gateway().lookup("missing").join());
    }

    @Test
    void http404ReturnsNotFoundWithoutRetry() {
        defaultResponse = response(404, "{}");

        assertEquals(DictionaryLookupResult.NOT_FOUND, gateway().lookup("missing").join());
        assertEquals(1, calls.get());
    }

    @Test
    void timeoutRetriesThenReturnsFound() {
        responses.add(response(200, FOUND_BODY, 200L));
        responses.add(response(200, FOUND_BODY));

        assertEquals(
                DictionaryLookupResult.FOUND,
                gateway(Duration.ofMillis(50), true, 2, false, 5, Clock.systemUTC())
                        .lookup("apple")
                        .join()
        );
        assertEquals(2, calls.get());
    }

    @Test
    void timeoutRetryExhaustionReturnsApiError() {
        responses.add(response(200, FOUND_BODY, 200L));
        responses.add(response(200, FOUND_BODY, 200L));

        assertEquals(
                DictionaryLookupResult.API_ERROR,
                gateway(Duration.ofMillis(50), true, 2, false, 5, Clock.systemUTC())
                        .lookup("apple")
                        .join()
        );
        assertEquals(2, calls.get());
    }

    @Test
    void http500RetriesThenReturnsFound() {
        responses.add(response(500, "{}"));
        responses.add(response(200, FOUND_BODY));

        assertEquals(DictionaryLookupResult.FOUND, gateway().lookup("apple").join());
        assertEquals(2, calls.get());
    }

    @Test
    void http429HonorsCappedRetryAfterAndThenReturnsFound() {
        responses.add(new ResponseSpec(429, "{}", 0L, "10"));
        responses.add(response(200, FOUND_BODY));

        assertEquals(
                DictionaryLookupResult.FOUND,
                gateway(Duration.ofSeconds(3), true, 2, false, 5, Clock.systemUTC())
                        .lookup("apple")
                        .join()
        );
        assertEquals(2, calls.get());
    }

    @Test
    void http400ReturnsApiErrorWithoutRetry() {
        defaultResponse = response(400, "{}");

        assertEquals(DictionaryLookupResult.API_ERROR, gateway().lookup("apple").join());
        assertEquals(1, calls.get());
    }

    @Test
    void invalidJsonReturnsApiErrorWithoutRetry() {
        defaultResponse = response(200, "not-json");

        assertEquals(DictionaryLookupResult.API_ERROR, gateway().lookup("apple").join());
        assertEquals(1, calls.get());
    }

    @Test
    void circuitOpensSkipsRequestsAndAllowsOneHalfOpenProbe() {
        MutableClock clock = new MutableClock();
        defaultResponse = response(503, "{}");
        FreeDictionaryApiGateway gateway = gateway(
                Duration.ofSeconds(2),
                false,
                1,
                true,
                3,
                clock
        );

        assertEquals(DictionaryLookupResult.API_ERROR, gateway.lookup("first").join());
        assertEquals(DictionaryLookupResult.API_ERROR, gateway.lookup("second").join());
        assertEquals(DictionaryLookupResult.API_ERROR, gateway.lookup("third").join());
        assertEquals(3, calls.get());

        assertEquals(DictionaryLookupResult.API_ERROR, gateway.lookup("skipped").join());
        assertEquals(3, calls.get());

        clock.advance(Duration.ofSeconds(61));
        defaultResponse = response(200, FOUND_BODY, 100L);
        CompletableFuture<DictionaryLookupResult> probe = gateway.lookup("probe");
        assertEquals(DictionaryLookupResult.API_ERROR, gateway.lookup("concurrent").join());
        assertEquals(DictionaryLookupResult.FOUND, probe.join());
        assertEquals(4, calls.get());

        defaultResponse = response(200, FOUND_BODY);
        assertEquals(DictionaryLookupResult.FOUND, gateway.lookup("after-recovery").join());
        assertEquals(5, calls.get());
    }

    @Test
    void blankWordReturnsNotFoundWithoutRequest() {
        assertEquals(DictionaryLookupResult.NOT_FOUND, gateway().lookup(" ").join());
        assertEquals(0, calls.get());
    }

    private FreeDictionaryApiGateway gateway() {
        return gateway(Duration.ofSeconds(2), true, 2, false, 5, Clock.systemUTC());
    }

    private FreeDictionaryApiGateway gateway(
            Duration timeout,
            boolean retryEnabled,
            int maxAttempts,
            boolean circuitEnabled,
            int failureThreshold,
            Clock clock
    ) {
        return new FreeDictionaryApiGateway(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                true,
                endpoint(),
                timeout,
                retryEnabled,
                maxAttempts,
                1L,
                circuitEnabled,
                failureThreshold,
                Duration.ofSeconds(60),
                clock
        );
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/dictionary/";
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        observedUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        ResponseSpec spec = responses.poll();
        if (spec == null) {
            spec = defaultResponse;
        }
        if (spec.delayMillis() > 0L) {
            try {
                Thread.sleep(spec.delayMillis());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = spec.body().getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (spec.retryAfter() != null) {
                exchange.getResponseHeaders().set("Retry-After", spec.retryAfter());
            }
            exchange.sendResponseHeaders(spec.status(), body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
            // The client can close the exchange first in timeout tests.
        }
    }

    private static ResponseSpec response(int status, String body) {
        return response(status, body, 0L);
    }

    private static ResponseSpec response(int status, String body, long delayMillis) {
        return new ResponseSpec(status, body, delayMillis, null);
    }

    private record ResponseSpec(
            int status,
            String body,
            long delayMillis,
            String retryAfter
    ) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
