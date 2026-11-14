package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicPlaybackTextBilibiliTest {
    @Test
    void mapsSelectionFailuresInAllLanguages() {
        I18nService i18n = I18nService.load(languageDir, "en");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);
        assertEquals("Bilibili did not provide an audio track for this video.", text.mapMusicLoadError("en", "BILIBILI_DASH_AUDIO_EMPTY"));
        assertEquals("Bilibili 未提供這部影片的音訊。", text.mapMusicLoadError("zh-TW", "BILIBILI_DASH_AUDIO_EMPTY"));
        assertEquals("Bilibili 未提供这部视频的音频。", text.mapMusicLoadError("zh-CN", "BILIBILI_DASH_AUDIO_EMPTY"));
        assertEquals("This Bilibili video's audio format is not supported.", text.mapMusicLoadError("en", "BILIBILI_AUDIO_FORMAT_UNSUPPORTED"));
        assertEquals("這部 Bilibili 影片的音訊格式尚不支援。", text.mapMusicLoadError("zh-TW", "BILIBILI_AUDIO_FORMAT_UNSUPPORTED"));
        assertEquals("这部 Bilibili 视频的音频格式尚不支持。", text.mapMusicLoadError("zh-CN", "BILIBILI_AUDIO_FORMAT_UNSUPPORTED"));
        assertEquals("Bilibili did not provide a usable audio URL for this video.", text.mapMusicLoadError("en", "BILIBILI_AUDIO_URL_MISSING"));
        assertEquals("Bilibili 未提供這部影片可用的音訊網址。", text.mapMusicLoadError("zh-TW", "BILIBILI_AUDIO_URL_MISSING"));
        assertEquals("Bilibili 未提供这部视频可用的音频网址。", text.mapMusicLoadError("zh-CN", "BILIBILI_AUDIO_URL_MISSING"));
        assertEquals("Bilibili returned invalid audio information for this video.", text.mapMusicLoadError("en", "BILIBILI_AUDIO_RESPONSE_INVALID"));
        assertEquals("Bilibili 回傳的影片音訊資料不完整或格式異常。", text.mapMusicLoadError("zh-TW", "BILIBILI_AUDIO_RESPONSE_INVALID"));
        assertEquals("Bilibili 返回的视频音频信息不完整或格式异常。", text.mapMusicLoadError("zh-CN", "BILIBILI_AUDIO_RESPONSE_INVALID"));
    }

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
