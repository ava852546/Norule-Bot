package com.norule.musicbot.service.minecraft;

import com.norule.musicbot.config.domain.MinecraftStatusConfig;
import com.norule.musicbot.domain.minecraft.MinecraftServerType;
import com.norule.musicbot.gateway.minecraft.MinecraftStatusGateway;
import com.norule.musicbot.gateway.minecraft.dto.McSrvStatResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftStatusServiceTest {

    @Test
    void defaultsToJavaWhenTypeMissing() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.response = new McSrvStatResponse(
                true,
                "1.2.3.4",
                25565,
                "1.21.1",
                5,
                20,
                List.of("hello", "world"),
                false
        );
        MinecraftStatusService service = new MinecraftStatusService(
                gateway,
                new MinecraftStatusConfig("NoRuleBot/1.0 contact: admin@norule.me", 15_000, 60)
        );

        MinecraftStatusService.QueryResult result = service.query("play.example.com", "");

        assertTrue(result.success());
        assertEquals(MinecraftServerType.JAVA, gateway.lastType);
        assertNotNull(result.status());
        assertEquals("hello\nworld", result.status().motd());
    }

    @Test
    void rejectsProtocolAddress() {
        RecordingGateway gateway = new RecordingGateway();
        MinecraftStatusService service = new MinecraftStatusService(
                gateway,
                new MinecraftStatusConfig("NoRuleBot/1.0 contact: admin@norule.me", 15_000, 60)
        );

        MinecraftStatusService.QueryResult result = service.query("https://play.example.com", "JAVA");

        assertFalse(result.success());
        assertEquals("INVALID_ADDRESS", result.errorCode());
        assertEquals(0, gateway.callCount);
    }

    @Test
    void usesInternalCacheWithinTtl() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.response = new McSrvStatResponse(
                true,
                "1.2.3.4",
                25565,
                "1.20.6",
                10,
                100,
                List.of("cached motd"),
                false
        );
        MinecraftStatusService service = new MinecraftStatusService(
                gateway,
                new MinecraftStatusConfig("NoRuleBot/1.0 contact: admin@norule.me", 15_000, 300)
        );

        MinecraftStatusService.QueryResult first = service.query("play.example.com", "JAVA");
        MinecraftStatusService.QueryResult second = service.query("play.example.com", "JAVA");

        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals(1, gateway.callCount);
        assertFalse(first.status().cached());
        assertTrue(second.status().cached());
    }

    @Test
    void wrapsGatewayFailuresAsFriendlyError() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.exception = new IOException("boom");
        MinecraftStatusService service = new MinecraftStatusService(
                gateway,
                new MinecraftStatusConfig("NoRuleBot/1.0 contact: admin@norule.me", 15_000, 60)
        );

        MinecraftStatusService.QueryResult result = service.query("play.example.com", "BEDROCK");

        assertFalse(result.success());
        assertEquals("UPSTREAM_ERROR", result.errorCode());
        assertEquals(1, gateway.callCount);
        assertEquals(MinecraftServerType.BEDROCK, gateway.lastType);
    }

    private static final class RecordingGateway implements MinecraftStatusGateway {
        private McSrvStatResponse response;
        private IOException exception;
        private int callCount;
        private MinecraftServerType lastType;

        @Override
        public McSrvStatResponse fetchStatus(String address,
                                             MinecraftServerType serverType,
                                             MinecraftStatusConfig config) throws IOException {
            callCount++;
            lastType = serverType;
            if (exception != null) {
                throw exception;
            }
            return response;
        }

        @Override
        public String buildIconUrl(String address) {
            return "https://api.mcsrvstat.us/icon/" + address;
        }
    }
}
