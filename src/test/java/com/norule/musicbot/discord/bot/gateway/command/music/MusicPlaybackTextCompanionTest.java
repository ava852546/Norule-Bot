package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicPlaybackTextCompanionTest {
    @TempDir
    Path languageDir;

    @Test
    void cipherPolicyFailureHasLocalizedTextRatherThanRawKey() {
        I18nService i18n = I18nService.load(languageDir, "en");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);
        for (String lang : new String[]{"en", "zh-TW", "zh-CN"}) {
            String message = text.mapMusicLoadError(lang, "CIPHER_REQUIRED_BUT_DISABLED");
            org.junit.jupiter.api.Assertions.assertFalse(message.contains("music.cipher_required_but_disabled"));
            org.junit.jupiter.api.Assertions.assertFalse(message.contains("CIPHER_REQUIRED_BUT_DISABLED"));
            org.junit.jupiter.api.Assertions.assertFalse(message.contains("Cipher"));
        }
    }

    @Test
    void mapsCompanionFailuresToLocalizedMessages() {
        I18nService i18n = I18nService.load(languageDir, "en");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);
        String expected = "This video cannot be played right now. Please contact an administrator.";

        for (String error : new String[] {
                "YOUTUBE_COMPANION_UNAVAILABLE",
                "YOUTUBE_COMPANION_TIMEOUT",
                "YOUTUBE_COMPANION_AUTH_FAILED",
                "YOUTUBE_COMPANION_BAD_REQUEST",
                "YOUTUBE_COMPANION_STREAM_UNAVAILABLE"
        }) {
            assertEquals(expected, text.mapMusicLoadError("en", error));
        }
        assertEquals(
                "This video cannot be played right now. Please contact an administrator.",
                text.companionPlaybackSkipped("en")
        );
    }

    @Test
    void traditionalChineseMessagesDoNotExposeBackendDetails() {
        I18nService i18n = I18nService.load(languageDir, "zh-TW");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);

        String expected = "\u76ee\u524d\u7121\u6cd5\u64ad\u653e\u6b64\u5f71\u7247\uff0c\u8acb\u806f\u7d61\u7ba1\u7406\u54e1\u3002";
        assertEquals(expected, text.mapMusicLoadError("zh-TW", "YOUTUBE_COMPANION_AUTH_FAILED"));
        assertEquals(expected, text.companionPlaybackSkipped("zh-TW"));
        assertEquals(expected, text.mapMusicLoadError("zh-TW", "CIPHER_REQUIRED_BUT_DISABLED: music.cipher.enabled=false"));
        assertEquals(expected, text.mapMusicLoadError("zh-TW", "YouTube playback failed: backend=COMPANION category=COMPANION_BAD_REQUEST"));
        org.junit.jupiter.api.Assertions.assertNotEquals(expected, text.mapMusicLoadError("zh-TW", "AUDIO_INVALID_INPUT"));
        org.junit.jupiter.api.Assertions.assertNotEquals(expected, text.mapMusicLoadError("zh-TW", "YOUTUBE_VIDEO_PRIVATE"));
    }
}
