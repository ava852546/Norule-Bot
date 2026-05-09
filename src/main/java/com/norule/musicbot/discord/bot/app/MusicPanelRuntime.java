package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelController;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelRefreshService;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelRenderer;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelStateStore;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

class MusicPanelRuntime {
    private static final long PANEL_MIN_EDIT_INTERVAL_MS = 3500L;

    private final MusicPanelStateStore panelStateStore;
    private final MusicPanelRenderer musicPanelRenderer;
    private final MusicPanelRefreshService musicPanelRefreshService;
    private final MusicPanelController musicPanelController;

    MusicPanelRuntime(MusicCommandService service,
                      ScheduledExecutorService scheduler,
                      long panelPeriodicRefreshMs) {
        this.panelStateStore = new MusicPanelStateStore();
        this.musicPanelRenderer = new MusicPanelRenderer(service);
        this.musicPanelRefreshService = new MusicPanelRefreshService(
                service,
                this.panelStateStore,
                this.musicPanelRenderer,
                scheduler,
                panelPeriodicRefreshMs,
                PANEL_MIN_EDIT_INTERVAL_MS
        );
        this.musicPanelController = new MusicPanelController(service, service::refreshPanel);
    }

    MusicPanelStateStore panelStateStore() { return panelStateStore; }
    MusicPanelRenderer musicPanelRenderer() { return musicPanelRenderer; }
    MusicPanelRefreshService musicPanelRefreshService() { return musicPanelRefreshService; }
    MusicPanelController musicPanelController() { return musicPanelController; }
    Map<Long, MusicPanelStateStore.PanelRef> panelRefs() { return panelStateStore.panelRefs(); }
}
