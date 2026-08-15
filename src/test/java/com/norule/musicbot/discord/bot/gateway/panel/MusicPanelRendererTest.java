package com.norule.musicbot.discord.bot.gateway.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPanelRendererTest {
    @Test
    void liveStreamUsesLiveIndicatorInsteadOfInvalidDuration() {
        assertEquals("\uD83D\uDD34 LIVE", MusicPanelRenderer.buildProgress(0L, 0L, true));
    }

    @Test
    void unknownDurationDoesNotDivideByZero() {
        assertEquals("00:13  --:--", MusicPanelRenderer.buildProgress(13_000L, 0L, false));
    }

    @Test
    void progressClampsPositionPastDuration() {
        String progress = MusicPanelRenderer.buildProgress(90_000L, 60_000L, false);

        assertTrue(progress.startsWith("01:00 "));
        assertTrue(progress.endsWith("\u25CF 01:00"));
        assertFalse(progress.contains("01:30"));
    }

    @Test
    void sourceMetadataUsesStableDisplayNames() {
        assertEquals("YouTube", MusicPanelRenderer.displaySource("youtube"));
        assertEquals("Spotify", MusicPanelRenderer.displaySource("spotify"));
        assertEquals("SoundCloud", MusicPanelRenderer.displaySource("soundcloud"));
        assertEquals("HTTP", MusicPanelRenderer.displaySource("http"));
        assertEquals("Unknown", MusicPanelRenderer.displaySource("unrecognized-source"));
    }

    @Test
    void durationSupportsHoursAndUnknownValues() {
        assertEquals("--:--", MusicPanelRenderer.formatDuration(0L));
        assertEquals("05:13", MusicPanelRenderer.formatDuration(313_000L));
        assertEquals("1:02:03", MusicPanelRenderer.formatDuration(3_723_000L));
    }
}
