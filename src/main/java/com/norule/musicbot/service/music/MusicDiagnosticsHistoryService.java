package com.norule.musicbot.service.music;

import com.norule.musicbot.domain.music.MusicDiagnostics;
import com.norule.musicbot.domain.music.MusicDiagnostics.Event;
import com.norule.musicbot.storage.sqlite.MusicDiagnosticsSqliteRepository;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Event-driven writer; diagnostic queries read memory and never enqueue maintenance. */
public final class MusicDiagnosticsHistoryService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicDiagnosticsHistoryService.class);
    private record Key(long guildId, String kind) {}
    private final Map<Key, Event> history = new ConcurrentHashMap<>();
    private final Map<Key, Event> dirty = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "music-diagnostics-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile MusicDiagnosticsSqliteRepository repository;
    private final java.util.concurrent.atomic.AtomicBoolean writing = new java.util.concurrent.atomic.AtomicBoolean();

    public void initialize(MusicDiagnosticsSqliteRepository repository) {
        for (Event event : repository.load()) history.putIfAbsent(new Key(event.guildId(), event.kind()), safe(event));
        this.repository = repository;
    }

    private Event safe(Event event) {
        return new Event(event.guildId(), event.kind(), event.at(), event.channelId(), event.requesterId(),
                MusicDiagnostics.text(event.source(), 60), MusicDiagnostics.text(event.title(), 240),
                MusicDiagnostics.publicSongUrl(event.publicUrl()), MusicDiagnostics.text(event.outcome(), 80));
    }

    public void record(Event raw) {
        Event event = safe(raw);
        Key key = new Key(event.guildId(), event.kind());
        history.compute(key, (ignored, old) -> old != null && old.at().isAfter(event.at()) ? old : event);
        if (repository == null) return;
        dirty.put(key, history.get(key));
        scheduleWrite();
    }

    private void scheduleWrite() {
        if (!writing.compareAndSet(false, true)) return;
        try {
            writer.execute(() -> {
                try {
                    while (!dirty.isEmpty()) {
                        for (var entry : dirty.entrySet()) {
                            if (dirty.remove(entry.getKey(), entry.getValue())) {
                                try { repository.save(entry.getValue()); }
                                catch (RuntimeException error) { LOGGER.warn("Music diagnostics persistence failed", error); }
                            }
                        }
                    }
                } finally {
                    writing.set(false);
                    if (!dirty.isEmpty()) scheduleWrite();
                }
            });
        } catch (RejectedExecutionException closed) {
            writing.set(false);
            LOGGER.debug("Music diagnostics writer is closed");
        }
    }

    public Event get(long guildId, String kind) { return history.get(new Key(guildId, kind)); }

    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) LOGGER.warn("Music diagnostics writer did not finish before shutdown");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
