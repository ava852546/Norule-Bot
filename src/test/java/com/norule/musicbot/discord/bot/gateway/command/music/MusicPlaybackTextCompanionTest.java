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
    void mapsCompanionFailuresToLocalizedMessages() {
        I18nService i18n = I18nService.load(languageDir, "en");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);
        String expected = "\u26A0\uFE0F No playable audio source is available for this track. "
                + "Please try again later or choose another track.";

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
                "\u26A0\uFE0F This track cannot be played right now and was skipped automatically.",
                text.companionPlaybackSkipped("en")
        );
    }

    @Test
    void traditionalChineseMessagesDoNotExposeBackendDetails() {
        I18nService i18n = I18nService.load(languageDir, "zh-TW");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);

        assertEquals(
                "\u26A0\uFE0F \u7121\u6cd5\u53d6\u5f97\u9019\u9996\u6b4c\u66f2\u7684\u53ef\u64ad\u653e\u97f3\u6e90\uff0c"
                        + "\u8acb\u7a0d\u5f8c\u518d\u8a66\u6216\u9078\u64c7\u5176\u4ed6\u6b4c\u66f2\u3002",
                text.mapMusicLoadError("zh-TW", "YOUTUBE_COMPANION_AUTH_FAILED")
        );
        assertEquals(
                "\u26A0\uFE0F \u76ee\u524d\u7121\u6cd5\u64ad\u653e\u9019\u9996\u6b4c\u66f2\uff0c"
                        + "\u5df2\u81ea\u52d5\u8df3\u904e\u3002",
                text.companionPlaybackSkipped("zh-TW")
        );
    }
}
