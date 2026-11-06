package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.lang.LanguageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DiscordTokenSourceTest {
    @TempDir Path directory;

    @Test void readsBomAndLineEndingsBesideSelectedConfigWithEnvironmentPrecedence() throws Exception {
        Path config = directory.resolve("nested/config.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(directory.resolve("token"), "wrong-directory");
        Files.writeString(config.resolveSibling("token"), "\uFEFF  synthetic-file-token\r\n", StandardCharsets.UTF_8);
        assertEquals("synthetic-file-token", DiscordTokenSource.read(config, ignored -> null));
        assertEquals("synthetic-env-token", DiscordTokenSource.read(config, ignored -> " synthetic-env-token\n"));
    }

    @Test void rejectsMissingEmptyAndMultilineCredentialsWithoutEchoingContent() throws Exception {
        Path config = directory.resolve("config.yml");
        assertEquals(DiscordTokenSource.MISSING, assertThrows(IllegalStateException.class,
                () -> DiscordTokenSource.read(config, ignored -> null)).getMessage());
        for (String invalid : new String[]{"  \r\n", "synthetic-secret\nsecond-line", "one two"}) {
            Files.writeString(directory.resolve("token"), invalid);
            var error = assertThrows(IllegalStateException.class, () -> DiscordTokenSource.read(config, ignored -> null));
            assertFalse(error.getMessage().contains("synthetic-secret"));
        }
    }

    @Test void safelyMigratesLegacyTokenAndRedactsBackupsWithoutRestoringYamlToken() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "token: synthetic-legacy\nprefix: '?'\nmusic:\n  spotify:\n    clientSecret: synthetic-secret\n");
        var initializer = new ConfigInitializer(new LanguageManager());
        initializer.initialize(config);
        String updated = Files.readString(config);
        assertFalse(updated.contains("token:"));
        assertEquals("synthetic-legacy", Files.readString(directory.resolve("token")));
        assertEquals("?", new BotConfigParser(ignored -> null).parse(config).getPrefix());
        try (var files = Files.list(directory)) {
            var backups = files.filter(p -> p.getFileName().toString().contains("backup-")).toList();
            assertFalse(backups.isEmpty());
            for (Path backup : backups) {
                String contents = Files.readString(backup);
                assertFalse(contents.contains("synthetic-legacy"));
                assertFalse(contents.contains("synthetic-secret"));
            }
        }
        initializer.initialize(config);
        assertEquals(updated, Files.readString(config));
    }

    @Test void conflictingCredentialStopsInitializationAndPreservesBothFiles() throws Exception {
        Path config = directory.resolve("config.yml");
        String original = "token: synthetic-legacy\nprefix: '?'\n";
        Files.writeString(config, original);
        Files.writeString(directory.resolve("token"), "synthetic-existing");
        assertThrows(IllegalStateException.class, () -> new ConfigInitializer(new LanguageManager()).initialize(config));
        assertEquals(original, Files.readString(config));
        assertEquals("synthetic-existing", Files.readString(directory.resolve("token")));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().contains("backup-")));
        }
    }

    @Test void parserNeverUsesLegacyYamlAsCredentialSource() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "token: synthetic-legacy\n");
        assertThrows(IllegalStateException.class, () -> new BotConfigParser(ignored -> null).parse(config));
    }

    @Test void malformedYamlDoesNotExposeSourceLinesInExceptionChain() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "token: synthetic-secret\nprefix: [unclosed\n");
        var failure = assertThrows(IllegalStateException.class, () -> new BotConfigParser(ignored -> null).parse(config));
        assertFalse(failure.toString().contains("synthetic-secret"));
        assertNull(failure.getCause());
    }
}
