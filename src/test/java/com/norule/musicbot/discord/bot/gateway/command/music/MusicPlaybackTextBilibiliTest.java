package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicPlaybackTextBilibiliTest {
    @TempDir
    Path languageDir;

    @Test
    void mapsRiskControlToLocalizedMessages() {
        I18nService i18n = I18nService.load(languageDir, "en");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);

        assertEquals(
                "Bilibili temporarily rejected the playback request. Please try again later.",
                text.mapMusicLoadError("en", "BILIBILI_RISK_CONTROL")
        );
        assertEquals(
                "Bilibili 暫時拒絕播放請求，請稍後再試。",
                text.mapMusicLoadError("zh-TW", "BILIBILI_RISK_CONTROL")
        );
        assertEquals(
                "Bilibili 暂时拒绝播放请求，请稍后再试。",
                text.mapMusicLoadError("zh-CN", "BILIBILI_RISK_CONTROL")
        );
    }
}
