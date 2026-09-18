package com.norule.musicbot.discord.bot.gateway.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Active message references and notices. Refresh scheduling belongs to the coordinator. */
public final class MusicPanelStateStore {
    private final Map<Long, PanelRef> panelByGuild = new ConcurrentHashMap<>();
    private final Map<Long, PanelNotice> panelNoticeByGuild = new ConcurrentHashMap<>();

    public Map<Long, PanelRef> panelRefs() {
        return Collections.unmodifiableMap(panelByGuild);
    }

    public PanelRef getPanelRef(long guildId) {
        return panelByGuild.get(guildId);
    }

    public boolean isActivePanel(long guildId, long channelId, long messageId) {
        PanelRef active = panelByGuild.get(guildId);
        return active != null && active.channelId == channelId && active.messageId == messageId;
    }

    public void putPanelRef(long guildId, PanelRef panelRef) {
        panelByGuild.put(guildId, panelRef);
    }

    public void activatePanelRef(long guildId, PanelRef panelRef, long refreshedAt) {
        panelRef.lastRefreshAt = refreshedAt;
        panelByGuild.put(guildId, panelRef);
    }

    public void clearPanelState(long guildId) {
        panelByGuild.remove(guildId);
        panelNoticeByGuild.remove(guildId);
    }

    public boolean compareAndClearPanelState(long guildId, long channelId, long messageId) {
        AtomicBoolean cleared = new AtomicBoolean();
        panelByGuild.computeIfPresent(guildId, (ignored, active) -> {
            if (active.channelId == channelId && active.messageId == messageId) {
                cleared.set(true);
                return null;
            }
            return active;
        });
        return cleared.get();
    }

    public long getLastRefreshAt(long guildId) {
        PanelRef ref = panelByGuild.get(guildId);
        return ref == null ? 0L : ref.lastRefreshAt;
    }

    public void markRefreshed(long guildId, PanelRef expected, long timestamp) {
        // Writing to an old reference can never alter the replacement panel's timestamp.
        if (panelByGuild.get(guildId) == expected) {
            expected.lastRefreshAt = timestamp;
        }
    }

    public ArrayList<Long> snapshotGuildIds() {
        return new ArrayList<>(panelByGuild.keySet());
    }

    public PanelNotice putPanelNotice(long guildId, String message, long expiresAtMillis) {
        PanelNotice notice = new PanelNotice(message, expiresAtMillis);
        panelNoticeByGuild.put(guildId, notice);
        return notice;
    }

    public PanelNotice getPanelNotice(long guildId, long nowMillis) {
        PanelNotice notice = panelNoticeByGuild.get(guildId);
        if (notice != null && notice.expiresAtMillis() <= nowMillis) {
            panelNoticeByGuild.remove(guildId, notice);
            return null;
        }
        return notice;
    }

    public boolean clearPanelNotice(long guildId, PanelNotice expected) {
        return expected != null && panelNoticeByGuild.remove(guildId, expected);
    }

    public record PanelNotice(String message, long expiresAtMillis) {
    }

    public static final class PanelRef {
        public final long channelId;
        public final long messageId;
        private volatile long lastRefreshAt;

        public PanelRef(long channelId, long messageId) {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }
}
