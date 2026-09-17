package com.norule.musicbot.service.shorturl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TurnstileVerifierTest {
    @Test
    void disabledNeedsNoSecretAndEnabledFailsFastWithoutConfiguration() {
        assertEquals(TurnstileVerifier.Result.ALLOWED, new TurnstileVerifier().verify("", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> new TurnstileVerifier.Options(true, "site", "", "host"));
        assertFalse(new TurnstileVerifier.Options(true, "site", "private-value", "host").toString().contains("private-value"));
    }

    @Test
    void verifiesSuccessAndHostnameAndFailsClosedOnInvalidProviderResponse() throws Exception {
        AtomicReference<String> response = new AtomicReference<>("{\"success\":true,\"hostname\":\"s.example.com\"}");
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            TurnstileVerifier verifier = new TurnstileVerifier(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/verify"));
            verifier.updateOptions(new TurnstileVerifier.Options(true, "site", "test-secret", "s.example.com"));
            assertEquals(TurnstileVerifier.Result.REJECTED, verifier.verify("", "ip"));
            assertEquals(TurnstileVerifier.Result.REJECTED, verifier.verify("x".repeat(2049), "ip"));
            assertEquals(0, calls.get());
            assertEquals(TurnstileVerifier.Result.ALLOWED, verifier.verify("challenge", "unknown"));
            response.set("{\"success\":true,\"hostname\":\"evil.example.com\"}");
            assertEquals(TurnstileVerifier.Result.REJECTED, verifier.verify("challenge", "unknown"));
            response.set("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");
            assertEquals(TurnstileVerifier.Result.REJECTED, verifier.verify("challenge", "unknown"));
            response.set("invalid");
            assertEquals(TurnstileVerifier.Result.UNAVAILABLE, verifier.verify("challenge", "unknown"));
        } finally { server.stop(0); }
    }
}
