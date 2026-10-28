package com.norule.musicbot.service.monitoring;

import com.norule.musicbot.config.domain.GatewayMonitoringConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Tracks a single Gateway lifecycle. Framework events are converted at the gateway boundary. */
public final class GatewayMonitorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayMonitorService.class);
    private final GatewayMonitoringConfig config;
    private final LongSupplier nanoTime;
    private long offlineSince;
    private long lastProgress;
    private long lastWarning;
    private boolean warned;
    private boolean offline = true;
    private boolean everConnected;
    private boolean stopping;
    private boolean recoveryRequested;
    private boolean recoveryBlocked;
    private boolean terminalRestartAllowed;
    private String status = "INITIALIZING";
    private String reason = "STARTUP";
    private Integer serverCloseCode;
    private Integer clientCloseCode;
    private int attempts;
    private boolean attemptPending;

    public GatewayMonitorService(GatewayMonitoringConfig config) {
        this(config, System::nanoTime);
    }

    public GatewayMonitorService(GatewayMonitoringConfig config, LongSupplier nanoTime) {
        this.config = config;
        this.nanoTime = nanoTime;
        offlineSince = nanoTime.getAsLong();
        lastProgress = offlineSince;
    }

    public synchronized void statusChanged(String previous, String current) {
        long now = nanoTime.getAsLong();
        status = current;
        lastProgress = now;
        if (!"CONNECTED".equals(current) && !"SHUTTING_DOWN".equals(current) && !"SHUTDOWN".equals(current)) {
            beginOffline(now);
        }
        if ("FAILED_TO_LOGIN".equals(current)) {
            recoveryBlocked = true;
        }
        if ("ATTEMPTING_TO_RECONNECT".equals(current)) {
            finishAttempt("FAILED", "UNKNOWN", now);
            attempts++;
            attemptPending = true;
        } else if ("WAITING_TO_RECONNECT".equals(current) || "RECONNECT_QUEUED".equals(current)) {
            finishAttempt("FAILED", "UNKNOWN", now);
        }
        String event = switch (current) {
            case "ATTEMPTING_TO_RECONNECT", "WAITING_TO_RECONNECT", "RECONNECT_QUEUED" -> "RECONNECTING";
            case "IDENTIFYING_SESSION" -> "IDENTIFY";
            default -> current;
        };
        LOGGER.info("[Gateway] event={} previous={} status={} attempt={} reason={} serverCloseCode={} clientCloseCode={}",
                event, previous, current, attempts, reason, serverCloseCode, clientCloseCode);
    }

    public synchronized void disconnected(Integer serverCode, Integer clientCode, String closeReason,
                                          boolean closedByServer, boolean restartForbidden) {
        long now = nanoTime.getAsLong();
        beginOffline(now);
        lastProgress = now;
        serverCloseCode = serverCode;
        clientCloseCode = clientCode;
        reason = clean(closeReason);
        recoveryBlocked |= restartForbidden;
        LOGGER.atLevel(stopping ? Level.INFO : Level.WARN).log("[Gateway] event=DISCONNECTED status={} closedByServer={} serverCloseCode={} clientCloseCode={} reason={} intentional={}",
                status, closedByServer, serverCode, clientCode, reason, stopping);
        finishAttempt("FAILED", "UNKNOWN", now);
    }

    public synchronized void connected(String sessionResult) {
        long now = nanoTime.getAsLong();
        LOGGER.info("[Gateway] event=SESSION_CONNECTED result={} resumed={} attempts={} offlineSeconds={} reason={} serverCloseCode={} clientCloseCode={}",
                sessionResult, "RESUMED".equals(sessionResult), attempts, offlineSeconds(now), reason,
                serverCloseCode, clientCloseCode);
        finishAttempt("SUCCESS", sessionResult, now);
        status = "CONNECTED";
        offline = false;
        everConnected = true;
        lastProgress = now;
        warned = false;
        attempts = 0;
        reason = "NONE";
        serverCloseCode = null;
        clientCloseCode = null;
        recoveryBlocked = false;
        terminalRestartAllowed = false;
        recoveryRequested = false;
    }

    public synchronized void terminated(int code, String closeReason, boolean retryable) {
        long now = nanoTime.getAsLong();
        beginOffline(now);
        lastProgress = now;
        status = "SHUTDOWN";
        reason = clean(closeReason);
        serverCloseCode = code;
        terminalRestartAllowed = retryable;
        recoveryBlocked |= !retryable;
        finishAttempt("FAILED", "UNKNOWN", now);
        LOGGER.atLevel(stopping ? Level.INFO : Level.ERROR).log("[Gateway] event=SHUTDOWN closeCode={} reason={} retryable={} intentional={}",
                code, reason, retryable, stopping);
    }

    /** Returns once per pending request when a supervisor should perform a controlled restart. */
    public synchronized boolean checkOffline(String observedStatus, int maxReconnectDelaySeconds) {
        if (stopping || !offline || "CONNECTED".equals(observedStatus)) {
            return false;
        }
        long now = nanoTime.getAsLong();
        long offlineSeconds = offlineSeconds(now);
        long stalledSeconds = TimeUnit.NANOSECONDS.toSeconds(now - lastProgress);
        if (offlineSeconds >= config.offlineWarningSeconds()
                && (!warned || now - lastWarning >= TimeUnit.SECONDS.toNanos(config.warningRepeatSeconds()))) {
            warned = true;
            lastWarning = now;
            LOGGER.warn("[Gateway] event=OFFLINE_WARNING status={} observedStatus={} offlineSeconds={} noProgressSeconds={} attempts={} reason={} serverCloseCode={} clientCloseCode={} recoveryEnabled={} recoveryBlocked={}",
                    status, observedStatus, offlineSeconds, stalledSeconds, attempts, reason, serverCloseCode,
                    clientCloseCode, config.recoveryEnabled(), recoveryBlocked);
        }
        if (recoveryRequested || !recoveryEligible(observedStatus, maxReconnectDelaySeconds, now)) {
            return false;
        }
        recoveryRequested = true;
        LOGGER.error("[Gateway] event=RECOVERY_REQUEST evidence={} status={} offlineSeconds={} noProgressSeconds={} attempts={} exitCode=75",
                "SHUTDOWN".equals(status) ? "JDA_TERMINATED" : "JDA_RECONNECT_STALLED",
                status, offlineSeconds, stalledSeconds, attempts);
        return true;
    }

    /** Rechecks evidence after cooldown; reconnect activity cancels the pending exit. */
    public synchronized boolean confirmRecovery(String observedStatus, int maxReconnectDelaySeconds) {
        if (!recoveryRequested) {
            return false;
        }
        if (!recoveryEligible(observedStatus, maxReconnectDelaySeconds, nanoTime.getAsLong())) {
            recoveryRequested = false;
            LOGGER.info("[Gateway] event=RECOVERY_CANCELLED status={} observedStatus={}", status, observedStatus);
            return false;
        }
        return true;
    }

    public synchronized void blockRecovery(String cause) {
        recoveryBlocked = true;
        recoveryRequested = false;
        LOGGER.error("[Gateway] event=RECOVERY_BLOCKED reason={}; offline warnings remain enabled", cause);
    }

    private boolean recoveryEligible(String observedStatus, int maxReconnectDelaySeconds, long now) {
        // A timeout alone is not evidence of failure: active retries and session-controller queues may be healthy.
        long stalledSeconds = TimeUnit.NANOSECONDS.toSeconds(now - lastProgress);
        long stallThreshold = Math.max(config.stalledReconnectSeconds(), 2L * maxReconnectDelaySeconds + 60L);
        boolean terminal = "SHUTDOWN".equals(status) && "SHUTDOWN".equals(observedStatus) && terminalRestartAllowed;
        boolean stalled = status.equals(observedStatus) && attempts > 0 && stalledSeconds >= stallThreshold
                && switch (status) {
                    case "DISCONNECTED", "ATTEMPTING_TO_RECONNECT", "WAITING_TO_RECONNECT",
                         "CONNECTING_TO_WEBSOCKET", "IDENTIFYING_SESSION", "AWAITING_LOGIN_CONFIRMATION" -> true;
                    default -> false;
                };
        return config.recoveryEnabled() && !stopping && offline && !recoveryBlocked && everConnected
                && offlineSeconds(now) >= config.restartAfterSeconds() && (terminal || stalled);
    }

    public synchronized void stop() {
        stopping = true;
    }

    private void beginOffline(long now) {
        if (!offline) {
            offline = true;
            offlineSince = now;
            warned = false;
        }
    }

    private void finishAttempt(String result, String sessionResult, long now) {
        if (attemptPending) {
            LOGGER.info("[Gateway] event=RECONNECT_RESULT attempt={} result={} session={} offlineSeconds={} reason={} serverCloseCode={} clientCloseCode={}",
                    attempts, result, sessionResult, offlineSeconds(now), reason, serverCloseCode, clientCloseCode);
            attemptPending = false;
        }
    }

    private long offlineSeconds(long now) {
        return offline ? TimeUnit.NANOSECONDS.toSeconds(now - offlineSince) : 0;
    }

    private static String clean(String text) {
        if (text == null || text.isBlank()) {
            return "UNKNOWN";
        }
        String singleLine = text.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        return singleLine.substring(0, Math.min(singleLine.length(), 200));
    }
}
