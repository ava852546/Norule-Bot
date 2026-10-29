package com.norule.musicbot.domain.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicDataServiceHistoryTest {
    @TempDir
    Path tempDir;

    @Test
    void emptyHistoryDoesNotBreakRecommendationFilteringOrHistoryDisplay() {
        MusicDataService service = new MusicDataService(tempDir, null, null, null);
        for (int limit : new int[] {-1, 0, 1, 10}) {
            assertTrue(service.getRecentHistory(1L, limit).isEmpty());
            assertFalse(service.wasRecentlyPlayed(1L, entry("New song"), limit));
        }
    }

    @Test
    void recentHistoryStillHonorsLimitAndDetectsPlayedTracks() {
        MusicDataService service = new MusicDataService(tempDir, null, null, null);
        var older = entry("Older song");
        var newest = entry("Newest song");
        service.recordTrackStarted(1L, older);
        service.recordTrackStarted(1L, newest);

        assertEquals(List.of(newest), service.getRecentHistory(1L, 1));
        assertEquals(List.of(newest, older), service.getRecentHistory(1L, 10));
        assertFalse(service.wasRecentlyPlayed(1L, older, 1));
        assertTrue(service.wasRecentlyPlayed(1L, older, 10));
        assertTrue(service.wasRecentlyPlayed(1L, newest, 0));
        assertFalse(service.wasRecentlyPlayed(1L, entry("Unplayed song"), 10));
    }

    private MusicDataService.PlaybackEntry entry(String title) {
        return new MusicDataService.PlaybackEntry(System.currentTimeMillis(), title, "Fixture Artist",
                "youtube", "", "", 180_000L, null, "");
    }
}
