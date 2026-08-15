package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackText;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelStateStore;
import com.norule.musicbot.domain.music.MusicPlayerService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

class PlaybackFailureNotifier {
    private static final long PANEL_NOTICE_DURATION_MILLIS = 30_000L;

    private final MusicCommandService service;
    private final MusicPanelStateStore panelStateStore;
    private final MusicPlaybackText musicPlaybackText;

    private final Map<Long, Long> playbackFailureLastAt = new ConcurrentHashMap<>();
    private final Map<Long, String> playbackFailureLastSig = new ConcurrentHashMap<>();

    PlaybackFailureNotifier(MusicCommandService service,
                            MusicPanelStateStore panelStateStore,
                            MusicPlaybackText musicPlaybackText) {
        this.service = service;
        this.panelStateStore = panelStateStore;
        this.musicPlaybackText = musicPlaybackText;
    }

    void reportPlaybackFailure(long guildId, MusicPlayerService.PlaybackFailure failure) {
        if (failure == null || failure.rawError() == null || failure.rawError().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sig = (failure.title() == null ? "-" : failure.title()) + "|" + failure.rawError().trim();
        Long lastAt = playbackFailureLastAt.get(guildId);
        String lastSig = playbackFailureLastSig.get(guildId);
        if (lastAt != null && now - lastAt < 8000L && sig.equals(lastSig)) {
            return;
        }
        playbackFailureLastAt.put(guildId, now);
        playbackFailureLastSig.put(guildId, sig);

        String lang = service.lang(guildId);
        String mapped = musicPlaybackText.mapMusicLoadError(lang, failure.rawError());
        String message = service.musicText(lang, "playback_failed", Map.of(
                "title", service.safe(failure.title(), 80),
                "error", service.safe(mapped, 180)
        ));
        MusicPanelStateStore.PanelNotice notice = panelStateStore.putPanelNotice(
                guildId,
                message,
                now + PANEL_NOTICE_DURATION_MILLIS
        );
        service.refreshPanel(guildId);
        service.scheduler().schedule(() -> {
            if (panelStateStore.clearPanelNotice(guildId, notice)) {
                service.refreshPanel(guildId);
            }
        }, PANEL_NOTICE_DURATION_MILLIS, TimeUnit.MILLISECONDS);
    }
}
