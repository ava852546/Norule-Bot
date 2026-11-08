package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelLocalizationTest {
    @TempDir
    Path languageDir;

    @Test
    void panelFailurePreservesPlaybackStatusAndHasFallbackInEveryLanguage() throws Exception {
        I18nService i18n = I18nService.load(languageDir, "en");
        var resolver = new MusicTextResolver(() -> i18n);
        for (String lang : java.util.List.of("en", "zh-TW", "zh-CN")) {
            String expected = i18n.t(lang, "music.panel_playback_unaffected", Map.of("error", "HTTP 404"));
            assertTrue(expected.contains("HTTP 404"));
            assertFalse(expected.contains("???"));
            assertFalse(expected.contains("music.panel_playback_unaffected"));
            java.nio.file.Files.writeString(languageDir.resolve(lang + ".yml"),
                    "music:\n  panel_playback_unaffected: music.panel_playback_unaffected\n");
            var missing = new MusicTextResolver(() -> I18nService.load(languageDir, lang));
            assertEquals(expected, missing.musicText(lang, "panel_playback_unaffected", Map.of("error", "HTTP 404")));
            assertEquals(expected, resolver.musicText(lang, "panel_playback_unaffected", Map.of("error", "HTTP 404")));
        }
    }

    @Test
    void loadsNewPanelAndEphemeralFlowKeysForAllSupportedLanguages() {
        I18nService i18n = I18nService.load(languageDir, "en");

        assertEquals("Search results expired", i18n.t("en", "music.search_expired_title"));
        assertEquals("搜尋結果已失效", i18n.t("zh-TW", "music.search_expired_title"));
        assertEquals("搜索结果已失效", i18n.t("zh-CN", "music.search_expired_title"));
        assertEquals("Playback Error", i18n.t("en", "music.panel_playback_error"));
        assertEquals("播放錯誤", i18n.t("zh-TW", "music.panel_playback_error"));
        assertEquals("播放错误", i18n.t("zh-CN", "music.panel_playback_error"));
        assertEquals(
                "Requested by <@1>",
                i18n.t("en", "music.panel_requested_by", Map.of("user", "<@1>"))
        );
        assertEquals(
                "佇列 3 首",
                i18n.t("zh-TW", "music.panel_queue_count", Map.of("count", "3"))
        );
        assertEquals(
                "队列位置：`2`",
                i18n.t("zh-CN", "music.queue_added_position", Map.of("title", "Song", "position", "2"))
                        .lines()
                        .reduce((first, second) -> second)
                        .orElseThrow()
        );
    }
}
