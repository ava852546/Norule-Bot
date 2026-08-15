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

        assertEquals(
                "Invidious Companion is currently unavailable. Please try again later.",
                text.mapMusicLoadError("en", "YOUTUBE_COMPANION_UNAVAILABLE")
        );
        assertEquals(
                "Invidious Companion timed out while preparing this track.",
                text.mapMusicLoadError("en", "YOUTUBE_COMPANION_TIMEOUT")
        );
        assertEquals(
                "Invidious Companion could not provide a playable audio stream for this track.",
                text.mapMusicLoadError("en", "YOUTUBE_COMPANION_STREAM_UNAVAILABLE")
        );
    }
}
