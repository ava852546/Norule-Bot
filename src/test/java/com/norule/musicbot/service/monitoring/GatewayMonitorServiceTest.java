package com.norule.musicbot.service.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.norule.musicbot.config.domain.GatewayMonitoringConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayMonitorServiceTest {
    private final AtomicLong clock = new AtomicLong();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GatewayMonitorService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    GatewayMonitorServiceTest() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void warnsAtFiveMinutesRepeatsAndResetsAfterRecovery() {
        GatewayMonitorService monitor = monitor(false);
        advance(299);
        assertFalse(monitor.checkOffline("DISCONNECTED", 900));
        assertEquals(0, count("event=OFFLINE_WARNING"));
        advance(1);
        monitor.checkOffline("DISCONNECTED", 900);
        assertEquals(1, count("event=OFFLINE_WARNING"));
        advance(299);
        monitor.checkOffline("DISCONNECTED", 900);
        assertEquals(1, count("event=OFFLINE_WARNING"));
        advance(1);
        monitor.checkOffline("DISCONNECTED", 900);
        assertEquals(2, count("event=OFFLINE_WARNING"));
        monitor.connected("RESUMED");
        advance(3600);
        monitor.checkOffline("CONNECTED", 900);
        assertEquals(2, count("event=OFFLINE_WARNING"));
        monitor.disconnected(null, null, "transport lost", false, false);
        advance(299);
        monitor.checkOffline("DISCONNECTED", 900);
        assertEquals(2, count("event=OFFLINE_WARNING"));
        advance(1);
        monitor.checkOffline("DISCONNECTED", 900);
        assertEquals(3, count("event=OFFLINE_WARNING"));
    }

    @Test
    void logsEveryAttemptAndDistinguishesResumedFromRecreated() {
        GatewayMonitorService monitor = monitor(false);
        monitor.connected("INITIAL_READY");
        monitor.disconnected(4009, 1000, "session expired\nforged-line", true, false);
        monitor.statusChanged("DISCONNECTED", "ATTEMPTING_TO_RECONNECT");
        monitor.statusChanged("ATTEMPTING_TO_RECONNECT", "WAITING_TO_RECONNECT");
        monitor.statusChanged("WAITING_TO_RECONNECT", "ATTEMPTING_TO_RECONNECT");
        monitor.connected("RECREATED");
        assertTrue(contains("attempt=1 result=FAILED session=UNKNOWN"));
        assertTrue(contains("attempt=2 result=SUCCESS session=RECREATED"));
        assertTrue(contains("result=RECREATED resumed=false"));
        assertTrue(contains("serverCloseCode=4009 clientCloseCode=1000"));
        assertTrue(contains("session expired forged-line"));
        monitor.disconnected(null, null, "network lost", false, false);
        monitor.statusChanged("DISCONNECTED", "ATTEMPTING_TO_RECONNECT");
        monitor.connected("RESUMED");
        assertTrue(contains("attempt=1 result=SUCCESS session=RESUMED"));
        assertTrue(contains("result=RESUMED resumed=true"));
    }

    @Test
    void healthyRetriesContinueEvenAfterLongOutage() {
        GatewayMonitorService monitor = monitor(true);
        monitor.connected("INITIAL_READY");
        monitor.disconnected(null, null, "network unavailable", false, false);
        for (int i = 0; i < 60; i++) {
            monitor.statusChanged("WAITING_TO_RECONNECT", "ATTEMPTING_TO_RECONNECT");
            advance(60);
            monitor.statusChanged("ATTEMPTING_TO_RECONNECT", "WAITING_TO_RECONNECT");
            assertFalse(monitor.checkOffline("WAITING_TO_RECONNECT", 900));
        }
        assertEquals(0, count("event=RECOVERY_REQUEST"));
    }

    @Test
    void stallsMustExceedJdaBackoffAndRestartIsRequestedOnlyOnce() {
        GatewayMonitorService monitor = monitor(true);
        monitor.connected("INITIAL_READY");
        monitor.disconnected(null, null, "transport lost", false, false);
        monitor.statusChanged("DISCONNECTED", "ATTEMPTING_TO_RECONNECT");
        advance(1800);
        assertFalse(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        advance(59);
        assertFalse(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        advance(1);
        assertTrue(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        assertFalse(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        assertEquals(1, count("event=RECOVERY_REQUEST"));
    }

    @Test
    void defaultConfigurationNeverRequestsRestart() {
        GatewayMonitorService monitor = monitor(false);
        monitor.connected("INITIAL_READY");
        monitor.statusChanged("CONNECTED", "ATTEMPTING_TO_RECONNECT");
        advance(10000);
        assertFalse(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
    }

    @Test
    void startupFatalCloseAndIntentionalShutdownNeverRestart() {
        GatewayMonitorService startup = monitor(true);
        startup.statusChanged("INITIALIZING", "ATTEMPTING_TO_RECONNECT");
        advance(10000);
        assertFalse(startup.checkOffline("ATTEMPTING_TO_RECONNECT", 900));

        GatewayMonitorService fatal = monitor(true);
        fatal.connected("INITIAL_READY");
        fatal.disconnected(4014, null, "disallowed intents", true, true);
        fatal.terminated(4014, "disallowed intents", false);
        advance(10000);
        assertFalse(fatal.checkOffline("SHUTDOWN", 900));

        GatewayMonitorService stopped = monitor(true);
        stopped.connected("INITIAL_READY");
        stopped.stop();
        stopped.terminated(1011, "shutdown", true);
        advance(10000);
        assertFalse(stopped.checkOffline("SHUTDOWN", 900));
    }

    @Test
    void retryableTerminationIsEvidenceButUnknownTerminationIsNot() {
        GatewayMonitorService retryable = monitor(true);
        retryable.connected("INITIAL_READY");
        retryable.terminated(1011, "internal server error", true);
        advance(1800);
        assertTrue(retryable.checkOffline("SHUTDOWN", 900));

        GatewayMonitorService unknown = monitor(true);
        unknown.connected("INITIAL_READY");
        unknown.terminated(-1, "unknown", false);
        advance(10000);
        assertFalse(unknown.checkOffline("SHUTDOWN", 900));
    }

    @Test
    void queueConnectedObservationAndStatusMismatchPreventFalseRecovery() {
        for (String observed : new String[]{"RECONNECT_QUEUED", "CONNECTED", "IDENTIFYING_SESSION"}) {
            GatewayMonitorService monitor = monitor(true);
            monitor.connected("INITIAL_READY");
            monitor.statusChanged("CONNECTED", "ATTEMPTING_TO_RECONNECT");
            if (observed.equals("RECONNECT_QUEUED")) {
                monitor.statusChanged("ATTEMPTING_TO_RECONNECT", "RECONNECT_QUEUED");
            }
            advance(10000);
            assertFalse(monitor.checkOffline(observed, 900), observed);
        }
    }

    private GatewayMonitorService monitor(boolean recovery) {
        return new GatewayMonitorService(new GatewayMonitoringConfig(60, 300, 300, recovery, 1800, 1800, 3, 60), clock::get);
    }

    @Test
    void recoveryDuringCooldownCancelsExitAndResumedRetryCancelsStallEvidence() {
        GatewayMonitorService monitor = monitor(true);
        monitor.connected("INITIAL_READY");
        monitor.statusChanged("CONNECTED", "ATTEMPTING_TO_RECONNECT");
        advance(2000);
        assertTrue(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        monitor.connected("RESUMED");
        assertFalse(monitor.confirmRecovery("CONNECTED", 900));

        monitor.statusChanged("CONNECTED", "ATTEMPTING_TO_RECONNECT");
        advance(2000);
        assertTrue(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        monitor.statusChanged("ATTEMPTING_TO_RECONNECT", "WAITING_TO_RECONNECT");
        assertFalse(monitor.confirmRecovery("WAITING_TO_RECONNECT", 900));
        assertFalse(monitor.checkOffline("WAITING_TO_RECONNECT", 900));
        advance(2000);
        assertTrue(monitor.checkOffline("WAITING_TO_RECONNECT", 900));
    }

    @Test
    void exhaustedBudgetBlocksExitButContinuesOfflineWarnings() {
        GatewayMonitorService monitor = monitor(true);
        monitor.connected("INITIAL_READY");
        monitor.statusChanged("CONNECTED", "ATTEMPTING_TO_RECONNECT");
        advance(2000);
        assertTrue(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        assertTrue(monitor.confirmRecovery("ATTEMPTING_TO_RECONNECT", 900));
        monitor.blockRecovery("PERSISTENT_RESTART_BUDGET_EXHAUSTED");
        advance(300);
        assertFalse(monitor.checkOffline("ATTEMPTING_TO_RECONNECT", 900));
        assertEquals(2, count("event=OFFLINE_WARNING"));
        assertEquals(1, count("event=RECOVERY_REQUEST"));
    }

    private void advance(long seconds) {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private long count(String text) {
        return logs.list.stream().filter(event -> event.getFormattedMessage().contains(text)).count();
    }

    private boolean contains(String text) {
        return count(text) > 0;
    }
}
