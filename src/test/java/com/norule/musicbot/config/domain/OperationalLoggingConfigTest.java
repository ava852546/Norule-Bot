package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OperationalLoggingConfigTest {
    @Test void defaultsDisabledAndParsesBothDeliveryModesWithoutChangingMonitoring() {
        assertEquals(OperationalLoggingConfig.disabled(), BotConfig.Discord.defaultValues().getLogging());
        for (var mode : OperationalLoggingConfig.DeliveryMode.values()) {
            var discord = BotConfig.Discord.fromMap(Map.of("logging", Map.of(
                    "jvmHeartbeat", Map.of("channelId", "123456789012345678", "deliveryMode", mode.name()),
                    "voiceAutoLeave", Map.of("channelId", "987654321012345678"))), null);
            assertEquals("123456789012345678", discord.getLogging().jvmChannelId());
            assertEquals("987654321012345678", discord.getLogging().voiceChannelId());
            assertEquals(mode, discord.getLogging().deliveryMode());
            assertEquals(GatewayMonitoringConfig.defaultValues(), discord.getMonitoring());
        }
    }

    @Test void invalidChannelsDisableAndUnknownModeUsesUpdateLatest() {
        for (String invalid : new String[]{"", "not-an-id", "-1", "18446744073709551616"}) {
            var config = new OperationalLoggingConfig(invalid, null, invalid);
            assertEquals("0", config.jvmChannelId());
            assertEquals("0", config.voiceChannelId());
        }
        var discord = BotConfig.Discord.fromMap(Map.of("logging", Map.of(
                "jvmHeartbeat", Map.of("deliveryMode", "unsupported"))), null);
        assertEquals(OperationalLoggingConfig.disabled(), discord.getLogging());
    }
}
