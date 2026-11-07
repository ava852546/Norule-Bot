package com.norule.musicbot.discord.bot.gateway.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelProgressRefreshTest {
    @Test
    void periodicProgressCannotRunBeforeTheConfiguredInterval() {
        long lastSuccess = 10_000L;
        assertFalse(MusicPanelRefreshService.progressRefreshDue(true, lastSuccess, 17_999L, 8_000L));
        assertTrue(MusicPanelRefreshService.progressRefreshDue(true, lastSuccess, 18_000L, 8_000L));
        assertFalse(MusicPanelRefreshService.progressRefreshDue(true, 18_000L, 18_750L, 8_000L));
        assertFalse(MusicPanelRefreshService.progressRefreshDue(true, lastSuccess, 18_000L, 10_000L));
    }

    @Test
    void inactiveGuildNeverRequestsProgressEvenAfterLongIdle() {
        assertFalse(MusicPanelRefreshService.progressRefreshDue(false, 0L, 600_000L, 8_000L));
    }
}
