package com.norule.musicbot.storage.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteDatabase {
    private final String jdbcUrl;

    public SqliteDatabase(Path dbFilePath) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not found (org.sqlite.JDBC)", e);
        }
        try {
            Path parent = dbFilePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare sqlite directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath.toAbsolutePath().normalize();
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public void initializeSchema(String... statements) {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            for (String sql : statements) {
                if (sql == null || sql.isBlank()) {
                    continue;
                }
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize sqlite schema", e);
        }
    }
}
