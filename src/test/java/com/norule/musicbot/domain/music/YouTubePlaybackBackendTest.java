package com.norule.musicbot.domain.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YouTubePlaybackBackendTest {
    @Test
    void acceptsCommonYoutubeSourceSpellings() {
        assertEquals(YouTubePlaybackBackend.YOUTUBE_SOURCE, YouTubePlaybackBackend.parse("youtube_source"));
        assertEquals(YouTubePlaybackBackend.YOUTUBE_SOURCE, YouTubePlaybackBackend.parse("YOUTUBE_SOURCE"));
        assertEquals(YouTubePlaybackBackend.YOUTUBE_SOURCE, YouTubePlaybackBackend.parse("youtube-source"));
    }

    @Test
    void acceptsCompanionAndFallsBackForUnknownValues() {
        assertEquals(YouTubePlaybackBackend.COMPANION, YouTubePlaybackBackend.parse("companion"));
        assertEquals(YouTubePlaybackBackend.YOUTUBE_SOURCE, YouTubePlaybackBackend.parse("typo"));
        assertTrue(YouTubePlaybackBackend.isRecognized("youtube-source"));
        assertFalse(YouTubePlaybackBackend.isRecognized("typo"));
    }
}
