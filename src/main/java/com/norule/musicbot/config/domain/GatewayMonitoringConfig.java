package com.norule.musicbot.config.domain;

/** Immutable startup settings; recovery must be explicitly enabled with an external process manager. */
public record GatewayMonitoringConfig(
        int resourceIntervalSeconds,
        int offlineWarningSeconds,
        int warningRepeatSeconds,
        boolean recoveryEnabled,
        int restartAfterSeconds,
        int stalledReconnectSeconds,
        int maxRecoveryRestarts,
        int restartCooldownSeconds
) {
    public GatewayMonitoringConfig {
        resourceIntervalSeconds = positive(resourceIntervalSeconds, 60);
        offlineWarningSeconds = positive(offlineWarningSeconds, 300);
        warningRepeatSeconds = positive(warningRepeatSeconds, 300);
        restartAfterSeconds = Math.max(offlineWarningSeconds, positive(restartAfterSeconds, 1800));
        stalledReconnectSeconds = positive(stalledReconnectSeconds, 1800);
        maxRecoveryRestarts = Math.min(10, maxRecoveryRestarts < 0 ? 3 : maxRecoveryRestarts);
        restartCooldownSeconds = Math.max(60, restartCooldownSeconds);
    }

    public static GatewayMonitoringConfig defaultValues() {
        return new GatewayMonitoringConfig(60, 300, 300, false, 1800, 1800, 3, 60);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
