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
    void warningEmbedUsesRequestedCopyAndLocalizedFallbacks() throws Exception {
        AtomicReference<I18nService> i18n = new AtomicReference<>(I18nService.load(directory, "en"));
        var resolver = new MusicTextResolver(i18n::get);
        var embed = MusicPlayReply.fallbackNotice(key -> resolver.musicText("zh-TW", key));
        assertEquals("音樂面板設定異常", embed.getTitle());
        assertEquals("目前暫時使用傳統模式，請透過 `/settings` 設定有效的預設音樂面板頻道，或切換為傳統模式以停止提醒。", embed.getDescription());
        assertEquals("僅執行指令者可見 · 保留提示", embed.getFooter().getText());
        assertEquals(0xF0B232, embed.getColorRaw());
        for (String language : List.of("en", "zh-TW", "zh-CN")) {
            var localized = MusicPlayReply.fallbackNotice(key -> resolver.musicText(language, key));
            assertFalse(localized.getTitle().startsWith("music."));
            assertTrue(localized.getDescription().contains("`/settings`"));
            assertFalse(localized.getFooter().getText().startsWith("music."));
        }
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_fallback_title: Reloaded title\n");
        i18n.set(I18nService.load(directory, "en"));
        assertEquals("Reloaded title", MusicPlayReply.fallbackNotice(key -> resolver.musicText("en", key)).getTitle());
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
