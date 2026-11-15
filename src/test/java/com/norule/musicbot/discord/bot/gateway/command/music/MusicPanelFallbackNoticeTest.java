package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelFallbackNoticeTest {
    @TempDir Path directory;

    @Test
    void supersededOperationHasLocalizedFallbackAfterReload() throws Exception {
        var i18n = new AtomicReference<>(I18nService.load(directory, "en"));
        var resolver = new MusicTextResolver(i18n::get);
        for (String language : List.of("en", "zh-TW", "zh-CN")) {
            assertFalse(resolver.musicText(language, "panel_operation_superseded").contains("panel_operation_superseded"));
        }
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_operation_superseded: music.panel_operation_superseded\n");
        i18n.set(I18nService.load(directory, "en"));
        assertTrue(resolver.musicText("en", "panel_operation_superseded").contains("Please try again"));
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_operation_superseded: Reloaded operation message\n");
        i18n.set(I18nService.load(directory, "en"));
        assertEquals("Reloaded operation message", resolver.musicText("en", "panel_operation_superseded"));
    }

    @Test
    void plainTextWarningIncludesSpecificPermissionsAndReloadFallbacks() throws Exception {
        var i18n = new AtomicReference<>(I18nService.load(directory, "en"));
        var resolver = new MusicTextResolver(i18n::get);
        for (String language : List.of("en", "zh-TW", "zh-CN")) {
            for (String permission : List.of("VIEW_CHANNEL", "MESSAGE_SEND", "MESSAGE_SEND_IN_THREADS", "MESSAGE_EMBED_LINKS")) {
                var mode = new com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver.Selection(
                        com.norule.musicbot.domain.music.MusicPanelMode.MODERN,
                        com.norule.musicbot.domain.music.MusicPanelMode.LEGACY, 123L, null,
                        "MISSING_EMBED_PERMISSION", List.of(permission));
                String notice = resolver.panelStatus(language, mode);
                assertTrue(notice.contains(resolver.musicText(language, "panel_permission_" + permission)));
                assertTrue(notice.contains("123"));
                assertTrue(notice.contains("MODERN"));
                assertTrue(notice.contains("LEGACY"));
                assertTrue(notice.contains("/settings"));
                assertFalse(notice.contains("music.panel_"));
                assertFalse(notice.contains("???"));
                assertTrue(notice.length() <= 1024, "Must fit the settings warning field");
            }
        }
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_permission_title: music.panel_permission_title\n");
        i18n.set(I18nService.load(directory, "en"));
        assertEquals("Music panel permissions missing", resolver.musicText("en", "panel_permission_title"));
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_permission_title: Reloaded title\n");
        i18n.set(I18nService.load(directory, "en"));
        assertEquals("Reloaded title", resolver.musicText("en", "panel_permission_title"));
        assertEquals("panel_reason_deleted", MusicTextResolver.panelReasonKey("UNKNOWN_CHANNEL"));
        for (String reason : List.of("CHANNEL_NOT_CACHED", "CHANNEL_VERIFICATION_PENDING", "IOException")) {
            assertEquals("panel_reason_verification", MusicTextResolver.panelReasonKey(reason));
        }
    }

    @Test
    void localizedNoticeExplainsEveryPlaySettingsAndLegacyOptOutAndPreservesReloadFallback() throws Exception {
        AtomicReference<I18nService> i18n = new AtomicReference<>(I18nService.load(directory, "en"));
        var resolver = new MusicTextResolver(i18n::get);
        assertEquals("新版模式（預設）", resolver.musicText("zh-TW", "panel_mode_modern"));
        assertEquals("新版模式（默认）", resolver.musicText("zh-CN", "panel_mode_modern"));
        for (String language : List.of("en", "zh-TW", "zh-CN")) {
            String text = resolver.musicText(language, "panel_fallback_legacy");
            assertTrue(text.contains("/play"));
            assertTrue(text.contains("/settings"));
            assertTrue(text.contains(language.equals("en") ? "Legacy" : "LEGACY"));
            assertFalse(text.contains("???"));
        }
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_fallback_legacy: music.panel_fallback_legacy\n");
        i18n.set(I18nService.load(directory, "en"));
        assertTrue(resolver.musicText("en", "panel_fallback_legacy").contains("every /play"));
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_fallback_legacy: Reloaded notice\n");
        i18n.set(I18nService.load(directory, "en"));
        assertEquals("Reloaded notice", resolver.musicText("en", "panel_fallback_legacy"));
    }

}
