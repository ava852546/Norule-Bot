package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.domain.music.MusicDiagnostics;
import com.norule.musicbot.service.music.MusicPanelBindingService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Active message references and notices. Refresh scheduling belongs to the coordinator. */
public final class MusicPanelStateStore {
    private final Map<Long, PanelRef> panelByGuild = new ConcurrentHashMap<>();
    private final Map<Long, PanelNotice> panelNoticeByGuild = new ConcurrentHashMap<>();
    private final Map<Long, Long> revisions = new ConcurrentHashMap<>();
    private MusicPanelBindingService bindings;
    private final Map<Long, MusicDiagnostics.Error> errors = new ConcurrentHashMap<>();
    public void recordDiagnosticError(long guildId, String reason) {
        errors.put(guildId, new MusicDiagnostics.Error(java.time.Instant.now(), "PANEL_API_FAILED", reason));
    }
    public MusicDiagnostics.Error diagnosticError(long guildId) { return errors.get(guildId); }

    public void initializePersistence(MusicPanelBindingService bindings) {
        this.bindings = bindings;
        for (var panel : bindings.load()) {
            panelByGuild.put(panel.guildId(), new PanelRef(panel.channelId(), panel.messageId()));
        }
    }

    private void persist(long guildId, PanelRef ref) {
        if (bindings != null) {
            bindings.save(guildId, ref.channelId, ref.messageId);
        }
    }

    public synchronized void forgetGuild(long guildId) {
        invalidate(guildId);
        if (bindings != null) {
            bindings.forgetGuild(guildId);
        }
        clearPanelState(guildId);
    }
    public Map<Long, PanelRef> panelRefs() {
        return Collections.unmodifiableMap(panelByGuild);
    }

    public PanelRef getPanelRef(long guildId) {
        return panelByGuild.get(guildId);
    }

    public boolean isActivePanel(long guildId, long channelId, long messageId) {
        PanelRef active = panelByGuild.get(guildId);
        return active != null && active.channelId == channelId && active.messageId == messageId
                && active.revision == revision(guildId);
    }

    public void putPanelRef(long guildId, PanelRef panelRef) {
        panelByGuild.put(guildId, panelRef);
    }

    public synchronized void activatePanelRef(long guildId, PanelRef panelRef, long refreshedAt) {
        persist(guildId, panelRef);
        panelRef.revision = revision(guildId);
        panelRef.lastRefreshAt = refreshedAt;
        panelByGuild.put(guildId, panelRef);
    }

    public long revision(long guildId) { return revisions.getOrDefault(guildId, 0L); }

    /** Retain the old ID for safe cleanup, but prevent old callbacks from using it. */
    public synchronized long invalidate(long guildId) {
        return revisions.merge(guildId, 1L, Long::sum);
    }

    public synchronized boolean activateIfCurrent(long guildId, long revision, PanelRef ref, long refreshedAt) {
        if (revision(guildId) != revision) return false;
        activatePanelRef(guildId, ref, refreshedAt);
        return true;
    }

    /** Remove only this binding, including its durable copy; never touch music settings. */
    public synchronized boolean forgetPanel(long guildId, PanelRef expected) {
        if (panelByGuild.get(guildId) != expected) return false;
        if (bindings != null) bindings.forgetGuild(guildId);
        panelByGuild.remove(guildId, expected);
        return true;
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
        if (panelByGuild.get(guildId) == expected && expected.revision == revision(guildId)) {
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
        private volatile long revision;

        public PanelRef(long channelId, long messageId) {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }
}
