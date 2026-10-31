package com.norule.musicbot.bootstrap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDependencyDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsCrossOriginCredentialAndDowngradeRedirects() {
        var origin = java.net.URI.create("https://repo.maven.apache.org/maven2/demo.jar");
        assertTrue(RuntimeDependencyDownloader.isTrustedRedirect(origin,
                origin.resolve("/maven2/redirected.jar")));
        for (String target : List.of("http://repo.maven.apache.org/demo.jar",
                "https://evil.example/demo.jar", "https://repo.maven.apache.org:444/demo.jar",
                "https://secret@repo.maven.apache.org/demo.jar")) {
            assertFalse(RuntimeDependencyDownloader.isTrustedRedirect(origin, java.net.URI.create(target)));
        }
    }

    @Test
    void refusesUntrustedRedirectWithoutRequestingItsDestination() throws IOException {
        AtomicInteger redirectedRequests = new AtomicInteger();
        try (TestServer destination = startServer(exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }); TestServer origin = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", destination.baseUrl() + "/demo.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        })) {
            var downloader = new RuntimeDependencyDownloader(settings(100, 0),
                    Map.of(RuntimeRepository.MAVEN_CENTRAL, origin.baseUrl()), ignored -> { },
                    System::nanoTime, ignored -> { });
            assertThrows(IOException.class, () -> downloader.download(artifact(), tempDir.resolve("redirect.part"), 1, 1));
            assertEquals(0, redirectedRequests.get());
        }
    }

    @Test
    void formatsPercentageWhenContentLengthIsKnown() {
        String message = RuntimeDependencyDownloader.formatProgress(
                12, 58, "file.jar", "central", 5_000L, 20_000L, 2_000.0, 2.5);

        assertTrue(message.contains("12/58"));
        assertTrue(message.contains("repository=central"));
        assertTrue(message.contains("downloaded=5000 bytes"));
        assertTrue(message.contains("total=20000 bytes"));
        assertTrue(message.contains("progress=25.0%"));
        assertTrue(message.contains("speed="));
        assertTrue(message.contains("elapsed=2.5s"));
        assertTrue(message.contains("eta="));
    }

    @Test
    void omitsPercentageAndEtaWhenContentLengthIsUnknown() {
        String message = RuntimeDependencyDownloader.formatProgress(
                3, 58, "file.jar", "lavalink-releases", 5_000L, -1L, 2_000.0, 2.5);

        assertTrue(message.contains("downloaded=5000 bytes"));
        assertTrue(message.contains("speed="));
        assertTrue(message.contains("elapsed=2.5s"));
        assertFalse(message.contains(" total="));
        assertFalse(message.contains("progress="));
        assertFalse(message.contains("eta="));
    }

    @Test
    void limitsProgressOutputToConfiguredInterval() {
        AtomicLong clock = new AtomicLong();
        List<String> messages = new ArrayList<>();
        RuntimeDependencyDownloader.DownloadProgressReporter reporter =
                new RuntimeDependencyDownloader.DownloadProgressReporter(
                        true, 2_000, 1, 1, "file.jar", "central", 10_000L, 0L, clock::get, messages::add);

        clock.set(1_000_000_000L);
        reporter.onBytes(1_000L);
        clock.set(2_000_000_000L);
        reporter.onBytes(2_000L);
        clock.set(3_999_000_000L);
        reporter.onBytes(3_000L);
        clock.set(4_000_000_000L);
        reporter.onBytes(4_000L);

        assertEquals(2, messages.size());
        assertTrue(messages.get(0).contains("downloaded=2000 bytes"));
        assertTrue(messages.get(1).contains("downloaded=4000 bytes"));
    }

    @Test
    void retriesAfterContinuousNoByteStall() throws IOException {
        byte[] expected = "complete jar".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        TestServer testServer = startServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(200, expected.length);
                exchange.getResponseBody().flush();
                sleep(250L);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, expected.length);
            exchange.getResponseBody().write(expected);
            exchange.close();
        });
        try (testServer) {
            List<String> messages = new ArrayList<>();
            List<Long> retryDelays = new ArrayList<>();
            RuntimeDependencyDownloader downloader = new RuntimeDependencyDownloader(
                    settings(50, 1), Map.of(RuntimeRepository.MAVEN_CENTRAL, testServer.baseUrl()),
                    messages::add, System::nanoTime,
                    retryDelays::add);
            Path runtimeLibs = Files.createDirectories(tempDir.resolve("stall-runtime-libs"));

            RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                    runtimeLibs, List.of(artifact()), settings(50, 1), downloader);

            assertEquals(2, requests.get());
            assertEquals(List.of(1_000L), retryDelays);
            assertEquals("complete jar", Files.readString(
                    runtimeLibs.resolve("demo-2.0.0.jar"), StandardCharsets.UTF_8));
            assertFalse(Files.exists(runtimeLibs.resolve("demo-2.0.0.jar.part")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("exception=StallTimeoutException")
                    && message.contains("retry 1/1")));
        }
    }

    @Test
    void abortsAfterRetriesAreExhaustedAndDeletesPartFile() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        TestServer testServer = startServer(exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1L);
            exchange.close();
        });
        try (testServer) {
            List<Long> retryDelays = new ArrayList<>();
            RuntimeDependencyDownloader downloader = new RuntimeDependencyDownloader(
                    settings(100, 2), Map.of(RuntimeRepository.MAVEN_CENTRAL, testServer.baseUrl()),
                    ignored -> { }, System::nanoTime,
                    retryDelays::add);
            Path runtimeLibs = Files.createDirectories(tempDir.resolve("failed-runtime-libs"));
            Path part = runtimeLibs.resolve("demo-2.0.0.jar.part");

            IOException failure = assertThrows(IOException.class,
                    () -> RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                            runtimeLibs, List.of(artifact()), settings(100, 2), downloader));

            assertEquals(3, requests.get());
            assertEquals(List.of(1_000L, 2_000L), retryDelays);
            assertFalse(Files.exists(part));
            assertFalse(Files.exists(runtimeLibs.resolve("demo-2.0.0.jar")));
            assertTrue(failure.getMessage().contains("artifact demo-2.0.0.jar"));
            assertTrue(failure.getMessage().contains("host=127.0.0.1"));
            assertTrue(failure.getMessage().contains("httpStatus=503"));
            assertTrue(failure.getMessage().contains("retries=2/2"));
            assertFalse(failure.getMessage().contains(testServer.baseUrl()));
        }
    }

    @Test
    void manifestCentralDoesNotFallbackToLavalink() throws IOException {
        AtomicInteger centralRequests = new AtomicInteger();
        AtomicInteger lavalinkRequests = new AtomicInteger();
        TestServer central = startServer(exchange -> {
            centralRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1L);
            exchange.close();
        });
        TestServer lavalink = startServer(exchange -> {
            lavalinkRequests.incrementAndGet();
            byte[] bytes = "wrong repository bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        try (central; lavalink) {
            RuntimeDependencyDownloader downloader = new RuntimeDependencyDownloader(
                    settings(100, 0),
                    Map.of(
                            RuntimeRepository.MAVEN_CENTRAL, central.baseUrl(),
                            RuntimeRepository.LAVALINK_RELEASES, lavalink.baseUrl()),
                    ignored -> { }, System::nanoTime, ignored -> { });
            Path runtimeLibs = Files.createDirectories(tempDir.resolve("no-fallback-runtime-libs"));

            assertThrows(IOException.class, () -> RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                    runtimeLibs, List.of(artifact(RuntimeRepository.MAVEN_CENTRAL)), settings(100, 0), downloader));

            assertEquals(1, centralRequests.get());
            assertEquals(0, lavalinkRequests.get());
        }
    }

    @Test
    void manifestLavalinkDoesNotProbeCentral() throws IOException {
        AtomicInteger centralRequests = new AtomicInteger();
        AtomicInteger lavalinkRequests = new AtomicInteger();
        byte[] bytes = "manifest repository bytes".getBytes(StandardCharsets.UTF_8);
        TestServer central = startServer(exchange -> {
            centralRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        TestServer lavalink = startServer(exchange -> {
            lavalinkRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        try (central; lavalink) {
            RuntimeDependencyDownloader downloader = new RuntimeDependencyDownloader(
                    settings(100, 0),
                    Map.of(
                            RuntimeRepository.MAVEN_CENTRAL, central.baseUrl(),
                            RuntimeRepository.LAVALINK_RELEASES, lavalink.baseUrl()),
                    ignored -> { }, System::nanoTime, ignored -> { });
            Path runtimeLibs = Files.createDirectories(tempDir.resolve("lavalink-only-runtime-libs"));

            RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                    runtimeLibs, List.of(artifact(RuntimeRepository.LAVALINK_RELEASES)), settings(100, 0), downloader);

            assertEquals(0, centralRequests.get());
            assertEquals(1, lavalinkRequests.get());
        }
    }

    private static RuntimeDependencyBootstrap.DependencyArtifact artifact() {
        return artifact(RuntimeRepository.MAVEN_CENTRAL);
    }

    private static RuntimeDependencyBootstrap.DependencyArtifact artifact(RuntimeRepository repository) {
        return new RuntimeDependencyBootstrap.DependencyArtifact("org.example", "demo", "2.0.0", "", "jar",
                repository, "a".repeat(64));
    }

    private static RuntimeDependencyBootstrap.BootstrapSettings settings(int stallTimeoutMs, int maxRetries) {
        return new RuntimeDependencyBootstrap.BootstrapSettings(
                true, false, false, false, 2_000, 1_000, 1_000, stallTimeoutMs, maxRetries);
    }

    private static TestServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return new TestServer(server, executor);
    }

    private static void sleep(long milliseconds) throws IOException {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Test server interrupted", e);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record TestServer(HttpServer server, ExecutorService executor) implements AutoCloseable {
        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
