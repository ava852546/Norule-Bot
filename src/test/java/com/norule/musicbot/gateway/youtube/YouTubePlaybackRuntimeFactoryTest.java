package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class YouTubePlaybackRuntimeFactoryTest {
    @Test
    void selectsYoutubeSourceBackendFromEnvironment() {
        YouTubePlaybackTrackFactory factory = createWith(Map.of(
                "YOUTUBE_PLAYBACK_BACKEND", "YOUTUBE_SOURCE"
        ));

        assertFalse(factory instanceof CompanionYouTubePlaybackTrackFactory);
    }

    @Test
    void selectsCompanionBackendWithoutOpeningGlobalHttpSource() {
        YouTubePlaybackTrackFactory factory = createWith(Map.of(
                "YOUTUBE_PLAYBACK_BACKEND", "COMPANION",
                "YOUTUBE_COMPANION_ENABLED", "false"
        ));

        assertInstanceOf(CompanionYouTubePlaybackTrackFactory.class, factory);
    }

    @Test
    void fallsBackToYoutubeSourceForUnknownBackend() {
        YouTubePlaybackTrackFactory factory = createWith(Map.of(
                "YOUTUBE_PLAYBACK_BACKEND", "not-a-backend"
        ));

        assertFalse(factory instanceof CompanionYouTubePlaybackTrackFactory);
    }

    private static YouTubePlaybackTrackFactory createWith(Map<String, String> environment) {
        return YouTubePlaybackRuntimeFactory.create(
                MusicConfig.defaultValues().getYoutube(),
                environment::get
        );
    }
}
