package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteShortUrlCaseUniquenessTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsCaseVariantOfLegacyCodeAtDatabaseBoundary() throws Exception {
        Path database = tempDir.resolve("legacy-uppercase.db");
        createLegacyDatabase(database);

        SqliteShortUrlRepository repository = new SqliteShortUrlRepository(database);
        long now = System.currentTimeMillis();
        ShortUrlService.ShortUrlEntry caseVariant = new ShortUrlService.ShortUrlEntry(
                "abc123", "https://example.com/new", now, now + 60_000L);

        assertEquals("AbC123", repository.findByCodeIgnoreCase("abc123").getCode());
        assertNull(repository.findByCode("abc123"));
        assertFalse(repository.saveIfAbsent(caseVariant));
        assertThrows(SQLException.class, () -> insertDirectly(database, "ABC123"));
    }

    private void createLegacyDatabase(Path database) throws Exception {
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
                    + "VALUES ('AbC123', 'https://example.com/legacy', 1, 4102444800000)");
        }
    }

    private void insertDirectly(Path database, String code) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.prepareStatement(
                     "INSERT INTO short_urls (code, target, created_at, expires_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, code);
            statement.setString(2, "https://example.com/direct");
            statement.setLong(3, 2L);
            statement.setLong(4, 4102444800000L);
            statement.executeUpdate();
        }
    }
}
