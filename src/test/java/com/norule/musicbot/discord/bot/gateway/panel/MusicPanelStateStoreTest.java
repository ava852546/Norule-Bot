package com.norule.musicbot.discord.bot.gateway.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPanelStateStoreTest {
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
