package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteShortUrlPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void migratesViewCountsAndPersistsLogChannel() throws Exception {
        Path database = tempDir.resolve("short-url.db");
        createLegacySchema(database);

        SqliteShortUrlRepository shortUrls = new SqliteShortUrlRepository(database);
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        long now = System.currentTimeMillis();
        shortUrls.save(new ShortUrlService.ShortUrlEntry(
                "link123", "https://example.com", now, now + 60_000L,
                0L, "owner-123", 0L
        ));
        images.save(new ImageShare(
                "image12", "image12.png", "image/png", 8L, now, now + 60_000L,
                "", "abc123"
        ).withOwnership(MediaOwnerType.DISCORD_USER, "owner-123", "quota-123"));

        assertEquals(1L, shortUrls.incrementViewCount("link123", now + 1_000L));
        assertEquals(2L, shortUrls.incrementViewCount("link123", now + 2_000L));
        assertEquals(2L, shortUrls.findByCode("link123").getViewCount());
        assertEquals("owner-123", shortUrls.findByCode("link123").getOwnerUserId());
        assertEquals(now + 2_000L, shortUrls.findByCode("link123").getLastAccessedAt());
        assertEquals(1L, images.incrementViewCount("image12", now + 3_000L));
        assertEquals(1L, images.findByCode("image12").viewCount());
        assertEquals("owner-123", images.findByCode("image12").ownerUserId());
        assertEquals(now + 3_000L, images.findByCode("image12").lastAccessedAt());
        assertEquals(List.of("link123"), shortUrls.findByOwnerUserId("owner-123", 0, 20).stream()
                .map(ShortUrlService.ShortUrlEntry::code)
                .toList());
        assertEquals(1L, shortUrls.countByOwnerUserId("owner-123"));
        assertEquals(1L, shortUrls.countByOwnerUserId("owner-123", true, now));
        assertEquals(0L, shortUrls.countByOwnerUserId("owner-123", false, now));
        assertEquals(List.of("image12"), images.findByOwnerUserId("owner-123", 0, 20).stream()
                .map(ImageShare::code)
                .toList());
        assertEquals(1L, images.countByOwnerUserId("owner-123"));
        assertEquals(1L, images.countByOwnerUserId("owner-123", true, now));
        assertEquals(0L, images.countByOwnerUserId("owner-123", false, now));
        assertEquals(0L, images.countByOwnerUserId("other-owner"));
        assertEquals("", shortUrls.findByCode("legacy").getOwnerUserId());
        assertEquals(0L, shortUrls.findByCode("legacy").getLastAccessedAt());

        shortUrls.saveLogChannelId(987654321L);
        assertEquals(987654321L, shortUrls.findLogChannelId());
        shortUrls.saveLogChannelId(null);
        assertNull(shortUrls.findLogChannelId());
    }

    @Test
    void enforcesUniqueCodesAndFindsLegacyCodesIgnoringAsciiCase() {
        Path database = tempDir.resolve("short-url-uniqueness.db");
        SqliteShortUrlRepository shortUrls = new SqliteShortUrlRepository(database);
        long now = System.currentTimeMillis();
        ShortUrlService.ShortUrlEntry legacy = new ShortUrlService.ShortUrlEntry(
                "LegacyCode", "https://example.com/legacy", now, now + 60_000L);
        ShortUrlService.ShortUrlEntry duplicate = new ShortUrlService.ShortUrlEntry(
                "LegacyCode", "https://example.com/duplicate", now, now + 60_000L);

        assertTrue(shortUrls.saveIfAbsent(legacy));
        assertFalse(shortUrls.saveIfAbsent(duplicate));
        assertEquals("LegacyCode", shortUrls.findByCodeIgnoreCase("legacycode").getCode());
    }

    private void createLegacySchema(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE short_urls (
                        code TEXT PRIMARY KEY,
                        target TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO short_urls (code, target, created_at, expires_at) "
                    + "VALUES ('legacy', 'https://example.com/legacy', 1, 4102444800000)");
            statement.execute("""
                    CREATE TABLE short_url_images (
                        code TEXT PRIMARY KEY,
                        storage_name TEXT NOT NULL,
                        content_type TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        password_hash TEXT NOT NULL DEFAULT ''
                    )
                    """);
        }
    }
}
