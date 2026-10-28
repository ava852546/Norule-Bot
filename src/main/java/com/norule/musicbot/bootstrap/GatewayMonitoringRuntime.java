package com.norule.musicbot.bootstrap;

import com.norule.musicbot.config.domain.GatewayMonitoringConfig;
import com.norule.musicbot.discord.bot.gateway.listener.GatewayLifecycleListener;
import com.norule.musicbot.service.monitoring.GatewayMonitorService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns monitoring resources and the bridge to process shutdown. */
final class GatewayMonitoringRuntime implements AutoCloseable {
    static final int RECOVERY_EXIT_CODE = 75;
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayMonitoringRuntime.class);
    private final GatewayMonitorService monitor;
    private final GatewayLifecycleListener listener;
    private final GatewayRecoveryBudget recoveryBudget;
    private final int cooldownSeconds;
    private final JvmResourceMonitor jvm = new JvmResourceMonitor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "NoRuleMonitoring");
        // Keep terminal JDA shutdown observable until cleanup or controlled recovery completes.
        thread.setDaemon(false);
        return thread;
    });

    GatewayMonitoringRuntime(GatewayMonitoringConfig config, Path baseDir) {
        monitor = new GatewayMonitorService(config);
        listener = new GatewayLifecycleListener(monitor);
        recoveryBudget = new GatewayRecoveryBudget(baseDir.resolve("data/monitoring/gateway-recovery.count"),
                config.maxRecoveryRestarts());
        cooldownSeconds = config.restartCooldownSeconds();
        scheduler.scheduleWithFixedDelay(() -> safely(jvm::sample),
                config.resourceIntervalSeconds(), config.resourceIntervalSeconds(), TimeUnit.SECONDS);
    }

    GatewayLifecycleListener listener() {
        return listener;
    }

    void watch(JDA jda) {
        scheduler.scheduleWithFixedDelay(() -> safely(() -> {
            if (monitor.checkOffline(jda.getStatus().name(), jda.getMaxReconnectDelay())) {
                LOGGER.warn("[Gateway] event=RECOVERY_COOLDOWN seconds={}", cooldownSeconds);
                scheduler.schedule(() -> safely(() -> recover(jda)), cooldownSeconds, TimeUnit.SECONDS);
            }
        }), 10, 10, TimeUnit.SECONDS);
    }

    private void recover(JDA jda) {
        if (!monitor.confirmRecovery(jda.getStatus().name(), jda.getMaxReconnectDelay())) {
            return;
        }
        try {
            if (!recoveryBudget.reserve()) {
                monitor.blockRecovery("PERSISTENT_RESTART_BUDGET_EXHAUSTED");
                return;
            }
        } catch (IOException failure) {
            LOGGER.error("[Gateway] Cannot persist recovery budget; automatic restart blocked", failure);
            monitor.blockRecovery("RESTART_BUDGET_STORAGE_FAILED");
            return;
        }
        // The existing cleanup hook runs; only the external process manager starts the next JVM.
        // A separate thread avoids shutdownNow() interrupting its own shutdown hook wait.
        new Thread(() -> {
            if (monitor.confirmRecovery(jda.getStatus().name(), jda.getMaxReconnectDelay())) {
                System.exit(RECOVERY_EXIT_CODE);
            }
        }, "NoRuleGatewayRecovery").start();
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOGGER.error("[Monitoring] Scheduled check failed; subsequent checks remain enabled", failure);
        }
    }

    @Override
    public void close() {
        monitor.stop();
        scheduler.shutdownNow();
        jvm.close();
    }
}
