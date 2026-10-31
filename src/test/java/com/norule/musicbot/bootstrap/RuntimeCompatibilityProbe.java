package com.norule.musicbot.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import moe.kyokobot.libdave.NativeDaveFactory;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngineManager;
import java.sql.Driver;
import java.util.ServiceLoader;

/** No JUnit dependency: this entire probe runs inside the isolated application loader. */
public final class RuntimeCompatibilityProbe {
    private RuntimeCompatibilityProbe() {
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = RuntimeCompatibilityProbe.class.getClassLoader();
        for (String name : new String[]{"net.dv8tion.jda.api.JDA",
                "com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager",
                "com.github.topi314.lavasrc.spotify.SpotifySourceManager",
                "moe.kyokobot.libdave.jda.LDJDADaveSessionFactory",
                "com.mysql.cj.jdbc.Driver", "com.fasterxml.jackson.databind.ObjectMapper"}) {
            if (Class.forName(name, true, loader).getClassLoader() != loader) {
                throw new IllegalStateException("Unexpected defining loader: " + name);
            }
        }
        if (!LoggerFactory.getILoggerFactory().getClass().getName().equals("ch.qos.logback.classic.LoggerContext")) {
            throw new IllegalStateException("Logback provider unavailable");
        }
        if (ServiceLoader.load(org.slf4j.spi.SLF4JServiceProvider.class).stream().count() != 1) {
            throw new IllegalStateException("Expected exactly one SLF4J provider");
        }
        if (ServiceLoader.load(Driver.class).stream().count() < 2) {
            throw new IllegalStateException("SQLite/MySQL SPI unavailable");
        }
        if (new ScriptEngineManager(loader).getEngineByName("javascript") == null) {
            throw new IllegalStateException("Rhino SPI unavailable");
        }
        Class.forName("org.sqlite.JDBC", true, loader);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite::memory:");
        config.setMaximumPoolSize(1);
        try (HikariDataSource pool = new HikariDataSource(config);
             var connection = pool.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 42")) {
            if (!result.next() || result.getInt(1) != 42) {
                throw new IllegalStateException("SQLite/Hikari query failed");
            }
        }
        // These execute native loading on the current test host; they do not test Discord audio.
        Class.forName("com.sun.jna.Native", true, loader).getField("POINTER_SIZE").get(null);
        Class.forName("club.minnced.opus.util.OpusLibrary", true, loader).getMethod("loadFromJar").invoke(null);
        Class.forName("com.sedmelluq.discord.lavaplayer.natives.ConnectorNativeLibLoader", true, loader)
                .getMethod("loadConnectorLibrary").invoke(null);
        NativeDaveFactory.ensureAvailable();
        LoggerFactory.getLogger(RuntimeCompatibilityProbe.class).info(
                "[BootstrapTest] isolated SPI, SQLite/Hikari, JNA, Opus, Lava natives and DAVE checks completed");
        ((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory()).stop();
    }
}
