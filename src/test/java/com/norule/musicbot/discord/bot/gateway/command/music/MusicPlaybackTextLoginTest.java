package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicPlaybackTextLoginTest {
    @TempDir
    Path languageDir;

    @Test
    void loginFailuresUseDedicatedMessageIncludingCompanionPlayback() {
        I18nService i18n = I18nService.load(languageDir, "en");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);
        for (String lang : new String[]{"en", "zh-TW", "zh-CN"}) {
            for (String error : new String[]{
                    "YOUTUBE_LOGIN_REQUIRED",
                    "youtube_login_required",
                    "This video requires login",
                    "Login required",
                    "Sign in to view this video",
                    "You must be signed in to view this video",
                    "YouTube playback failed: backend=COMPANION category=LOGIN_REQUIRED"
            }) {
                assertEquals(i18n.t(lang, "music.youtube_login_required"),
                        text.mapMusicLoadError(lang, error), lang + ": " + error);
            }
        }
        assertEquals("This link requires login to play. The bot only supports videos that do not require login.",
                text.mapMusicLoadError("en", "YOUTUBE_LOGIN_REQUIRED"));
    }

    @Test
    void otherFailuresKeepTheirExistingMessages() {
        I18nService i18n = I18nService.load(languageDir, "en");
        MusicPlaybackText text = new MusicPlaybackText(() -> i18n);
        Map<String, String> cases = Map.of(
                "YOUTUBE_BOT_DETECTED", "music.youtube_bot_detected",
                "Sign in to confirm you're not a bot", "music.load_failed_generic",
                "Sign in to confirm your age", "music.youtube_age_restricted",
                "This is a private video. Sign in to view it", "music.youtube_private_video",
                "YOUTUBE_COMPANION_AUTH_FAILED", "music.youtube_playback_admin_required",
                "YouTube playback failed: backend=COMPANION category=COMPANION_BAD_REQUEST",
                    "music.youtube_playback_admin_required",
                "AUDIO_INVALID_INPUT", "music.audio_invalid_input",
                "YOUTUBE_VIDEO_UNAVAILABLE", "music.youtube_unavailable",
                "Unknown error", "music.load_failed_generic"
        );
        for (String lang : new String[]{"en", "zh-TW", "zh-CN"}) {
            cases.forEach((error, key) -> assertEquals(i18n.t(lang, key),
                    text.mapMusicLoadError(lang, error), lang + ": " + error));
        }
    }
}
