package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayMonitoringConfigTest {
    @Test
    void existingDiscordSettingsReceiveSafeMonitoringDefaults() {
        var config = BotConfig.Discord.fromMap(Map.of(), null).getMonitoring();
        assertEquals(60, config.resourceIntervalSeconds());
        assertEquals(300, config.offlineWarningSeconds());
        assertFalse(config.recoveryEnabled());
    }

    @Test
    void parsesMonitoringAndPreservesLoginRetrySettings() {
        var discord = BotConfig.Discord.fromMap(Map.of(
                "loginRetry", Map.of("maxAttempts", 4),
                "monitoring", Map.of("resourceIntervalSeconds", 30, "offlineWarningSeconds", 600,
                        "warningRepeatSeconds", 120, "recoveryEnabled", true,
                        "restartAfterSeconds", 900, "stalledReconnectSeconds", 1200,
                        "maxRecoveryRestarts", 5, "restartCooldownSeconds", 90)), null);
        assertEquals(4, discord.getLoginRetry().getMaxAttempts());
        assertEquals(new GatewayMonitoringConfig(30, 600, 120, true, 900, 1200, 5, 90), discord.getMonitoring());
        assertTrue(discord.getMonitoring().recoveryEnabled());
    }

    @Test
    void rejectsNonpositiveIntervalsAndRestartCannotPrecedeWarning() {
        var config = new GatewayMonitoringConfig(0, 600, -1, true, 1, 0, -1, 0);
        assertEquals(60, config.resourceIntervalSeconds());
        assertEquals(300, config.warningRepeatSeconds());
        assertEquals(600, config.restartAfterSeconds());
        assertEquals(1800, config.stalledReconnectSeconds());
        assertEquals(3, config.maxRecoveryRestarts());
        assertEquals(60, config.restartCooldownSeconds());
    }
}
