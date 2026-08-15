package com.norule.musicbot.discord.bot.gateway.panel;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public final class MusicPanelStateStore {
    private final Map<Long, PanelRef> panelByGuild = new ConcurrentHashMap<>();
    private final Map<Long, Long> panelLastRefreshAt = new ConcurrentHashMap<>();
    private final Map<Long, String> panelLastSignature = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> delayedPanelRefreshByGuild = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> delayedPanelRefreshForceByGuild = new ConcurrentHashMap<>();
    private final Set<Long> panelRefreshingGuilds = ConcurrentHashMap.newKeySet();
    private final Map<Long, RefreshRequest> pendingPanelRefreshByGuild = new ConcurrentHashMap<>();

    public Map<Long, PanelRef> panelRefs() {
        return panelByGuild;
    }

    public PanelRef getPanelRef(long guildId) {
        return panelByGuild.get(guildId);
    }

    public boolean isActivePanel(long guildId, long channelId, long messageId) {
        PanelRef active = panelByGuild.get(guildId);
        return active != null
                && active.channelId == channelId
                && active.messageId == messageId;
    }

    public synchronized void putPanelRef(long guildId, PanelRef panelRef) {
        panelByGuild.put(guildId, panelRef);
    }

    public synchronized PanelRef removePanelRef(long guildId) {
        return panelByGuild.remove(guildId);
    }

    public synchronized void clearPanelState(long guildId) {
        panelByGuild.remove(guildId);
        panelLastSignature.remove(guildId);
        panelLastRefreshAt.remove(guildId);
        pendingPanelRefreshByGuild.remove(guildId);
        delayedPanelRefreshForceByGuild.remove(guildId);
        cancelDelayedRefreshTask(guildId);
    }

    public synchronized boolean compareAndClearPanelState(long guildId,
                                                          long expectedChannelId,
                                                          long expectedMessageId) {
        PanelRef active = panelByGuild.get(guildId);
        if (active == null
                || active.channelId != expectedChannelId
                || active.messageId != expectedMessageId
                || !panelByGuild.remove(guildId, active)) {
            return false;
        }
        panelLastSignature.remove(guildId);
        panelLastRefreshAt.remove(guildId);
        pendingPanelRefreshByGuild.remove(guildId);
        delayedPanelRefreshForceByGuild.remove(guildId);
        cancelDelayedRefreshTask(guildId);
        return true;
    }

    public long getLastRefreshAt(long guildId) {
        return panelLastRefreshAt.getOrDefault(guildId, 0L);
    }

    public void putLastRefreshAt(long guildId, long timestamp) {
        panelLastRefreshAt.put(guildId, timestamp);
    }

    public String getLastSignature(long guildId) {
        return panelLastSignature.get(guildId);
    }

    public void putLastSignature(long guildId, String signature) {
        panelLastSignature.put(guildId, signature);
    }

    public void requestRefresh(long guildId, boolean force, boolean immediate, boolean periodicOnly) {
        RefreshRequest incoming = new RefreshRequest(force, immediate, periodicOnly);
        pendingPanelRefreshByGuild.merge(guildId, incoming, RefreshRequest::merge);
    }

    public RefreshRequest pollRefreshRequest(long guildId) {
        return pendingPanelRefreshByGuild.remove(guildId);
    }

    public boolean hasPendingRefresh(long guildId) {
        return pendingPanelRefreshByGuild.containsKey(guildId);
    }

    public boolean startRefreshing(long guildId) {
        return panelRefreshingGuilds.add(guildId);
    }

    public void finishRefreshing(long guildId) {
        panelRefreshingGuilds.remove(guildId);
    }

    public ScheduledFuture<?> getDelayedRefreshTask(long guildId) {
        return delayedPanelRefreshByGuild.get(guildId);
    }

    public void putDelayedRefreshTask(long guildId, ScheduledFuture<?> task) {
        delayedPanelRefreshByGuild.put(guildId, task);
    }

    public void removeDelayedRefreshTask(long guildId) {
        delayedPanelRefreshByGuild.remove(guildId);
    }

    public void mergeDelayedRefreshForce(long guildId, boolean force) {
        delayedPanelRefreshForceByGuild.merge(guildId, force, (current, incoming) -> current || incoming);
    }

    public boolean pollDelayedRefreshForce(long guildId) {
        return Boolean.TRUE.equals(delayedPanelRefreshForceByGuild.remove(guildId));
    }

    public synchronized void cancelDelayedRefreshTask(long guildId) {
        ScheduledFuture<?> task = delayedPanelRefreshByGuild.remove(guildId);
        delayedPanelRefreshForceByGuild.remove(guildId);
        if (task != null) {
            task.cancel(false);
        }
    }

    public ArrayList<Long> snapshotGuildIds() {
        return new ArrayList<>(panelByGuild.keySet());
    }

    public record RefreshRequest(boolean force, boolean immediate, boolean periodicOnly) {
        RefreshRequest merge(RefreshRequest other) {
            return new RefreshRequest(
                    force || other.force,
                    immediate || other.immediate,
                    periodicOnly && other.periodicOnly
            );
        }
    }

    public static final class PanelRef {
        public final long channelId;
        public final long messageId;

        public PanelRef(long channelId, long messageId) {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }
}
