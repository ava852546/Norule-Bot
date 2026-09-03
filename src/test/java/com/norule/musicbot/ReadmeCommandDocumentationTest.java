package com.norule.musicbot;

import com.norule.musicbot.discord.bot.gateway.command.registry.DiscordCommandCatalog;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadmeCommandDocumentationTest {
    private static final Path README = Path.of("README.md");
    private static final List<Path> HELP_FILES = List.of(
            Path.of("src/main/resources/defaults/lang/en.yml"),
            Path.of("src/main/resources/defaults/lang/zh-TW.yml"),
            Path.of("src/main/resources/defaults/lang/zh-CN.yml")
    );
    private static final Pattern HELP_BLOCK = Pattern.compile("(?ms)^help:\\R.*?(?=^settings:\\R)");
    private static final List<String> RETIRED_ENTRY_SYNTAX = List.of(
            "/settings action:",
            "/設定 選項:",
            "/ticket action:",
            "/客服單 action:",
            "/number-chain action:",
            "/數字接龍 action:",
            "/delete-messages channel",
            "/quest-notification"
    );

    @Test
    void readmeDocumentsEveryRegisteredSlashCommand() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);
        List<String> missing = new DiscordCommandCatalog().buildCommands().stream()
                .map(CommandData::getName)
                .filter(name -> !readme.contains("/" + name))
                .toList();

        assertTrue(missing.isEmpty(), "README is missing registered slash commands: " + missing);
    }

    @Test
    void readmeAndHelpAvoidRetiredCommandEntrySyntax() throws IOException {
        assertDoesNotContainRetiredSyntax("README", Files.readString(README, StandardCharsets.UTF_8));
        for (Path helpFile : HELP_FILES) {
            String source = Files.readString(helpFile, StandardCharsets.UTF_8);
            Matcher matcher = HELP_BLOCK.matcher(source);
            assertTrue(matcher.find(), "Missing help block in " + helpFile);
            assertDoesNotContainRetiredSyntax(helpFile.toString(), matcher.group());
        }
    }

    @Test
    void editedDocumentationUsesUtf8WithoutBom() throws IOException {
        for (Path path : allDocumentationFiles()) {
            byte[] content = Files.readAllBytes(path);
            assertFalse(hasUtf8Bom(content), path + " must not contain a UTF-8 BOM");
            Files.readString(path, StandardCharsets.UTF_8);
        }
    }

    private static void assertDoesNotContainRetiredSyntax(String sourceName, String source) {
        for (String retiredSyntax : RETIRED_ENTRY_SYNTAX) {
            assertFalse(source.contains(retiredSyntax),
                    sourceName + " still contains retired syntax: " + retiredSyntax);
        }
    }

    private static List<Path> allDocumentationFiles() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(README), HELP_FILES.stream()).toList();
    }

    private static boolean hasUtf8Bom(byte[] content) {
        return content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF;
    }
}
