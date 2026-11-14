package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.YoutubeFailureClassifier;
import dev.lavalink.youtube.clients.skeleton.Client;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Per-operation observations only: never schedules work or retries clients. */
final class YoutubeClientDiagnostics implements AutoCloseable {
    private static final ThreadLocal<YoutubeClientDiagnostics> CURRENT = new ThreadLocal<>();
    private final YoutubeClientDiagnostics previous = CURRENT.get();
    private final LinkedHashMap<String, String> states = new LinkedHashMap<>();
    private final LinkedHashSet<String> attempted = new LinkedHashSet<>();
    private final String stage;
    private final String correlationId;
    private Throwable failure;

    YoutubeClientDiagnostics(Client[] configured, String stage, String correlationId) {
        Arrays.stream(configured).forEach(client -> states.put(client.getIdentifier(), "CONFIGURED"));
        this.stage = stage;
        this.correlationId = correlationId;
        CURRENT.set(this);
    }

    static void skipped(Client client) {
        var current = CURRENT.get();
        if (current != null && !current.attempted.contains(client.getIdentifier())) {
            current.states.put(client.getIdentifier(), "SKIPPED");
        }
    }

    static <T> T observe(Client client, Operation<T> operation) throws dev.lavalink.youtube.CannotBeLoaded, java.io.IOException {
        var current = CURRENT.get();
        if (current == null) return operation.run();
        String name = client.getIdentifier();
        current.attempted.add(name);
        current.states.put(name, "ATTEMPTED");
        try {
            T result = operation.run();
            current.states.put(name, result == null ? "SKIPPED" : "SUCCESS");
            return result;
        } catch (dev.lavalink.youtube.CannotBeLoaded | java.io.IOException | RuntimeException failure) {
            current.states.put(name, "FAILED:" + new YoutubeFailureClassifier().classify(failure).category());
            throw failure;
        }
    }

    void failed(Throwable failure) { this.failure = failure; }

    String summary() {
        return "configuredClients=" + states.keySet() + " attemptedClients=" + attempted + " clientStates=" + states;
    }

    String clientFailuresSummary(com.norule.musicbot.domain.music.YoutubeFailureReport report) {
        return report.clientFailures().stream().filter(client -> attempted.contains(client.clientName()))
                .map(client -> client.clientName() + ":" + client.category())
                .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        var report = failure == null ? null : new YoutubeFailureClassifier().classify(failure);
        // Stream connection/decoding may fail after loadFormats returned successfully.
        if (report != null) report.clientFailures().forEach(client -> {
            if (attempted.contains(client.clientName())) {
                states.put(client.clientName(), "FAILED:" + client.category());
            }
        });
        var logger = LoggerFactory.getLogger(YoutubeClientDiagnostics.class);
        // Metadata already emits one application summary with these observations.
        if ("METADATA_DISCOVERY".equals(stage) && failure != null) return;
        var event = failure == null ? logger.atDebug() : logger.atWarn();
        event.log("[NoRule] YouTube clients: correlationId={} stage={} configuredClients={} attemptedClients={} clientStates={} category={}",
                correlationId, stage, states.keySet(), attempted, states, report == null ? "NONE" : report.category());
    }

    @FunctionalInterface
    interface Operation<T> { T run() throws dev.lavalink.youtube.CannotBeLoaded, java.io.IOException; }
}
