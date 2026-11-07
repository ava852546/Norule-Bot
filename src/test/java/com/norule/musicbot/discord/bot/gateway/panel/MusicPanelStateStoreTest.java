package com.norule.musicbot.discord.bot.gateway.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPanelStateStoreTest {
    @Test
    void settingsInvalidationRejectsOldActivationsAndRefreshesButKeepsTheCleanupReference() {
        var store = new MusicPanelStateStore();
        var old = new MusicPanelStateStore.PanelRef(10, 100);
        store.activatePanelRef(1, old, 123);
        long oldRevision = store.revision(1);
        long currentRevision = store.invalidate(1);
        assertFalse(store.isActivePanel(1, 10, 100));
        assertEquals(old, store.getPanelRef(1));
        assertFalse(store.activateIfCurrent(1, oldRevision, new MusicPanelStateStore.PanelRef(20, 200), 456));
        store.markRefreshed(1, old, 999);
        assertEquals(123, store.getLastRefreshAt(1));
        assertTrue(store.activateIfCurrent(1, currentRevision, new MusicPanelStateStore.PanelRef(30, 300), 456));
        assertTrue(store.isActivePanel(1, 30, 300));
        assertFalse(store.forgetPanel(1, old));
    }

    @Test
    void rateLimitDiagnosticsOnlyRecordTheMatchingManagedMessageRoute() {
        var store = new MusicPanelStateStore();
        store.putPanelRef(1, new MusicPanelStateStore.PanelRef(10, 100));
        store.putPanelRef(2, new MusicPanelStateStore.PanelRef(20, 200));
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var panels = new MusicPanelRefreshService(null, store, null, scheduler, 0, null);
        try {
            panels.observeRateLimit("PATCH", "/channels/10/messages/100");
            assertEquals("PANEL_ROUTE_429", store.diagnosticError(1).reason());
            assertNull(store.diagnosticError(2));
            panels.observeRateLimit("PATCH", "/channels/20/messages/999");
            panels.observeRateLimit("GET", "/channels/20/messages/200");
            assertNull(store.diagnosticError(2));
            assertEquals(100, store.getPanelRef(1).messageId);
            assertEquals(0, store.getLastRefreshAt(1));
        } finally {
            panels.close();
            scheduler.shutdownNow();
        }
    }
    @Test
    void unknownMessageClearsTheMatchingStoredReference() {
        MusicPanelStateStore store = new MusicPanelStateStore();
        store.putPanelRef(1L, new MusicPanelStateStore.PanelRef(10L, 100L));

        assertTrue(store.compareAndClearPanelState(1L, 10L, 100L));
        assertNull(store.getPanelRef(1L));
    }

    @Test
    void staleRefreshCannotClearAReplacementPanel() {
        MusicPanelStateStore store = new MusicPanelStateStore();
        store.putPanelRef(1L, new MusicPanelStateStore.PanelRef(10L, 100L));

        store.putPanelRef(1L, new MusicPanelStateStore.PanelRef(10L, 200L));

        assertFalse(store.compareAndClearPanelState(1L, 10L, 100L));
        MusicPanelStateStore.PanelRef active = store.getPanelRef(1L);
        assertEquals(10L, active.channelId);
        assertEquals(200L, active.messageId);
    }

    @Test
    void staleCompletionCannotChangeReplacementTimestamp() {
        MusicPanelStateStore store = new MusicPanelStateStore();
        var old = new MusicPanelStateStore.PanelRef(10L, 100L);
        store.activatePanelRef(1L, old, 123L);
        store.activatePanelRef(1L, new MusicPanelStateStore.PanelRef(10L, 200L), 456L);
        store.markRefreshed(1L, old, 999L);
        assertEquals(456L, store.getLastRefreshAt(1L));
    }

    @Test
    void olderNoticeExpiryCannotClearANewerNotice() {
        MusicPanelStateStore store = new MusicPanelStateStore();
        MusicPanelStateStore.PanelNotice older = store.putPanelNotice(1L, "older", 200L);
        MusicPanelStateStore.PanelNotice newer = store.putPanelNotice(1L, "newer", 300L);

        assertFalse(store.clearPanelNotice(1L, older));
        assertEquals(newer, store.getPanelNotice(1L, 250L));
        assertNull(store.getPanelNotice(1L, 300L));
    }
}
