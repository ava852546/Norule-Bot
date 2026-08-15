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
    void pendingRefreshRequestsAreCoalescedAndEscalated() {
        MusicPanelStateStore store = new MusicPanelStateStore();

        store.requestRefresh(1L, false, false, true);
        store.requestRefresh(1L, false, false, false);
        store.requestRefresh(1L, true, true, false);

        MusicPanelStateStore.RefreshRequest request = store.pollRefreshRequest(1L);
        assertTrue(request.force());
        assertTrue(request.immediate());
        assertFalse(request.periodicOnly());
        assertFalse(store.hasPendingRefresh(1L));
    }

    @Test
    void refreshLockIsHeldUntilExplicitCompletion() {
        MusicPanelStateStore store = new MusicPanelStateStore();

        assertTrue(store.startRefreshing(1L));
        assertFalse(store.startRefreshing(1L));

        store.finishRefreshing(1L);

        assertTrue(store.startRefreshing(1L));
    }

    @Test
    void delayedRefreshPreservesStrongestForceIntent() {
        MusicPanelStateStore store = new MusicPanelStateStore();

        store.mergeDelayedRefreshForce(1L, false);
        store.mergeDelayedRefreshForce(1L, true);
        store.mergeDelayedRefreshForce(1L, false);

        assertTrue(store.pollDelayedRefreshForce(1L));
        assertFalse(store.pollDelayedRefreshForce(1L));
    }

    @Test
    void activatingPanelDoesNotDropRefreshQueuedDuringCreation() {
        MusicPanelStateStore store = new MusicPanelStateStore();
        store.requestRefresh(1L, true, false, false);

        store.activatePanelRef(
                1L,
                new MusicPanelStateStore.PanelRef(10L, 100L),
                "rendered-state",
                123L
        );

        assertTrue(store.hasPendingRefresh(1L));
        assertEquals("rendered-state", store.getLastSignature(1L));
        assertEquals(123L, store.getLastRefreshAt(1L));
        assertTrue(store.isActivePanel(1L, 10L, 100L));
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
