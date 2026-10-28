package com.norule.musicbot.discord.bot.gateway.listener;

import com.neovisionaries.ws.client.WebSocketFrame;
import com.norule.musicbot.service.monitoring.GatewayMonitorService;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;

/** Converts JDA lifecycle events into framework-independent monitoring calls. */
public final class GatewayLifecycleListener extends ListenerAdapter {
    private final GatewayMonitorService monitor;

    public GatewayLifecycleListener(GatewayMonitorService monitor) {
        this.monitor = monitor;
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        monitor.statusChanged(event.getOldStatus().name(), event.getNewStatus().name());
    }

    @Override
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        WebSocketFrame server = event.getServiceCloseFrame();
        WebSocketFrame client = event.getClientCloseFrame();
        CloseCode code = event.getCloseCode();
        String reason = server != null ? server.getCloseReason() : client != null ? client.getCloseReason() : null;
        if (reason == null || reason.isBlank()) {
            reason = code == null ? "TRANSPORT_CLOSED_WITHOUT_REASON" : code.getMeaning();
        }
        monitor.disconnected(server == null ? null : server.getCloseCode(),
                client == null ? null : client.getCloseCode(),
                reason,
                event.isClosedByServer(), code != null && !code.isReconnect());
    }

    @Override
    public void onReady(ReadyEvent event) {
        monitor.connected("INITIAL_READY");
    }

    @Override
    public void onSessionResume(SessionResumeEvent event) {
        monitor.connected("RESUMED");
    }

    @Override
    public void onSessionRecreate(SessionRecreateEvent event) {
        monitor.connected("RECREATED");
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        CloseCode code = event.getCloseCode();
        monitor.terminated(event.getCode(), code == null ? "UNKNOWN_CLOSE_CODE" : code.getMeaning(),
                code != null && code.isReconnect() && code != CloseCode.GRACEFUL_CLOSE);
    }
}
