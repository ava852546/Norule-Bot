package com.norule.musicbot.discord.bot.gateway.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelProgressRefreshTest {
    @Test
    void periodicProgressCannotRunBeforeTheConfiguredInterval() {
        long lastSuccess = 10_000L;
        assertFalse(MusicPanelRefreshService.progressRefreshDue(true, lastSuccess, 39_999L, 30_000L));
        assertTrue(MusicPanelRefreshService.progressRefreshDue(true, lastSuccess, 40_000L, 30_000L));
        assertFalse(MusicPanelRefreshService.progressRefreshDue(true, 40_000L, 40_750L, 30_000L));
        assertFalse(MusicPanelRefreshService.progressRefreshDue(true, lastSuccess, 40_000L, 60_000L));
    }

    @Test
    void inactiveGuildNeverRequestsProgressEvenAfterLongIdle() {
        assertFalse(MusicPanelRefreshService.progressRefreshDue(false, 0L, 600_000L, 30_000L));
    }
}
