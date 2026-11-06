package com.norule.musicbot.service.music;

import com.norule.musicbot.domain.music.MusicDiagnostics;
import com.norule.musicbot.domain.music.MusicDiagnostics.Event;
import com.norule.musicbot.storage.sqlite.MusicDiagnosticsSqliteRepository;
import com.norule.musicbot.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MusicDiagnosticsHistoryServiceTest {
    @TempDir Path directory;

    @Test void persistsSeparatedHistoryAndLateResultsCannotOverwriteNewerRequests() {
        var repository = new MusicDiagnosticsSqliteRepository(new SqliteDatabase(directory.resolve("bot.db")));
        Instant time = Instant.parse("2026-11-06T15:00:00Z");
        try (var service = new MusicDiagnosticsHistoryService()) {
            service.initialize(repository);
            service.record(new Event(1, "START", time, null, 3L, "YouTube", "Song", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "AUDIO_FRAME_CONFIRMED"));
            service.record(new Event(1, "REQUEST", time, 5L, 3L, "/play", null, null, "RECEIVED"));
            service.record(new Event(1, "REQUEST", time.plusSeconds(1), 6L, 4L, "WebUI", null, null, "REJECTED"));
            service.record(new Event(1, "REQUEST", time, 5L, 3L, "/play", null, null, "ACCEPTED"));
            service.record(new Event(2, "REQUEST", time, 8L, 9L, "prefix play", null, null, "ACCEPTED"));
            assertEquals("Song", service.get(1, "START").title());
            assertEquals(4L, service.get(1, "REQUEST").requesterId());
            assertNull(service.get(2, "START"));
        }
        try (var restored = new MusicDiagnosticsHistoryService()) {
            restored.initialize(repository);
            assertEquals("Song", restored.get(1, "START").title());
            assertEquals("REJECTED", restored.get(1, "REQUEST").outcome());
            assertEquals(9L, restored.get(2, "REQUEST").requesterId());
        }
    }

    @Test void dropsSensitiveOrArbitraryUrlsBeforePersistence() {
        assertFalse(MusicDiagnostics.text("Audio https://cdn.test/file?token=secret", 240).contains("secret"));
        var repository = new MusicDiagnosticsSqliteRepository(new SqliteDatabase(directory.resolve("bot.db")));
        for (String url : new String[]{"https://www.youtube.com/watch?v=dQw4w9WgXcQ&token=secret", "https://cdn.test/audio?sig=secret",
                "https://secret@youtube.com/watch?v=dQw4w9WgXcQ", "file:///secrets", "https://youtube.com/watch?v=dQw4w9WgXcQ#token"}) {
            assertNull(MusicDiagnostics.publicSongUrl(url));
            try (var service = new MusicDiagnosticsHistoryService()) {
                service.initialize(repository);
                service.record(new Event(1, "START", Instant.now(), null, null, "HTTP", "x".repeat(10000), url, "CONFIRMED"));
            }
            var stored = repository.load().getFirst();
            assertNull(stored.publicUrl());
            assertTrue(stored.title().length() <= 240);
        }
    }
}
