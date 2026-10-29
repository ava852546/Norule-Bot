package com.norule.musicbot.shorturl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.norule.musicbot.ShortUrlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShortUrlExpirationTest {
    @TempDir Path dir;

    @Test
    void logsOnceAcrossScansAndRepeatedRestartsAndKeepsCodeReserved() {
        Path db = dir.resolve("restart.db");
        var repository = new SqliteShortUrlRepository(db);
        var expired = entry("expired", 1L, 2L);
        repository.save(expired);
        repository.save(entry("active", 3L, Long.MAX_VALUE));
        Logger logger = (Logger) LoggerFactory.getLogger(ShortUrlService.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var service = new ShortUrlService(repository);
            service.cleanupExpired();
            service.cleanupExpired();
            for (int i = 0; i < 3; i++) {
                repository = new SqliteShortUrlRepository(db);
                service = new ShortUrlService(repository);
                service.cleanupExpired();
            }
            List<String> events = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("event=SHORT_URL_EXPIRED ")).toList();
            assertEquals(1, events.size());
            assertTrue(events.getFirst().contains("codeReleased=false"));
            assertNotNull(repository.findByCode("expired"));
            assertFalse(repository.saveIfAbsent(entry("expired", 4L, Long.MAX_VALUE)));
            assertNull(service.resolve("expired"));
            assertTrue(service.isExpiredShortUrl("expired"));
            assertNotNull(service.resolve("active"));
            assertEquals(ShortUrlService.MutationResult.FORBIDDEN,
                    service.mutateOwned("expired", "other", "", true));
            assertEquals(ShortUrlService.MutationResult.SUCCESS,
                    service.mutateOwned("expired", "owner", "", true));
            assertTrue(repository.saveIfAbsent(entry("expired", 4L, Long.MAX_VALUE)));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void simultaneousScannersHaveOnlyOneWinner() throws Exception {
        Path db = dir.resolve("concurrent.db");
        var first = new SqliteShortUrlRepository(db);
        var second = new SqliteShortUrlRepository(db);
        first.save(entry("expired", 1L, 2L));
        // Both instances read the same pending row before either attempts the conditional update.
        var firstCandidate = first.findUnprocessedExpired(10L).getFirst();
        var secondCandidate = second.findUnprocessedExpired(10L).getFirst();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return first.markExpiredProcessed(firstCandidate, 10L);
            });
            var b = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return second.markExpiredProcessed(secondCandidate, 10L);
            });
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertTrue(first.findUnprocessedExpired(10L).isEmpty());
        assertTrue(new SqliteShortUrlRepository(db).findUnprocessedExpired(10L).isEmpty());
    }

    @Test
    void historicalBaselineIsOneTimeAndPreservesRows() throws Exception {
        Path db = dir.resolve("legacy.db");
        createLegacy(db);
        var repository = new SqliteShortUrlRepository(db);
        assertTrue(repository.findUnprocessedExpired(System.currentTimeMillis()).isEmpty());
        assertNotNull(repository.findByCode("historical"));
        assertTrue(new ShortUrlService(repository).isExpiredShortUrl("historical"));
        assertFalse(repository.saveIfAbsent(entry("historical", 3L, Long.MAX_VALUE)));
        // A URL that expires while the process is stopped must still produce a new event.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE short_urls SET expires_at = 2 WHERE code = 'active'");
        }
        repository = new SqliteShortUrlRepository(db);
        assertEquals(List.of("active"), repository.findUnprocessedExpired(System.currentTimeMillis())
                .stream().map(ShortUrlService.ShortUrlEntry::code).toList());
    }

    @Test
    void interruptedBaselineRollsBackMarkerAndRetries() throws Exception {
        Path db = dir.resolve("retry.db");
        createLegacy(db);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_baseline BEFORE UPDATE ON short_urls"
                    + " BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        }
        assertThrows(IllegalStateException.class, () -> new SqliteShortUrlRepository(db));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_baseline");
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM short_url_settings"
                    + " WHERE setting_key = 'expired_processed_baseline_v1'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
        assertTrue(new SqliteShortUrlRepository(db)
                .findUnprocessedExpired(System.currentTimeMillis()).isEmpty());
    }

    @Test
    void rejectsActiveRowsAndStaleCandidatesAfterCodeReuse() {
        var repository = new SqliteShortUrlRepository(dir.resolve("identity.db"));
        var expired = entry("reused", 1L, 2L);
        repository.save(expired);
        assertFalse(repository.markExpiredProcessed(expired, 1L));
        repository.deleteOwned("reused", "owner", 1L);
        repository.save(entry("reused", 3L, 4L));
        assertFalse(repository.markExpiredProcessed(expired, 10L));
        assertEquals(1, repository.findUnprocessedExpired(10L).size());
    }

    private ShortUrlService.ShortUrlEntry entry(String code, long created, long expires) {
        return new ShortUrlService.ShortUrlEntry(code, "https://example.com", created, expires,
                0L, "owner", 0L);
    }

    private void createLegacy(Path db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE short_urls (code TEXT PRIMARY KEY, target TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO short_urls VALUES"
                    + " ('historical', 'https://example.com/old', 1, 2),"
                    + " ('active', 'https://example.com/active', 1, 9223372036854775807)");
        }
    }
}
