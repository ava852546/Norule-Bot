package com.norule.musicbot;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModerationServiceTest {

    @Test
    void chainedExpressionIsTreatedAsNumberInNumberChain() throws Exception {
        Path tempDir = Files.createTempDirectory("moderation-service-test");
        ModerationService service = new ModerationService(tempDir);

        long guildId = 1001L;
        long channelId = 2002L;

        service.setNumberChainEnabled(guildId, true);
        service.setNumberChainChannelId(guildId, channelId);

        ModerationService.NumberChainResult first = service.processNumberChainMessage(guildId, channelId, "1");
        assertEquals(ModerationService.NumberChainType.ACCEPTED, first.getType());

        ModerationService.NumberChainResult chained = service.processNumberChainMessage(guildId, channelId, "1-2+3");
        assertEquals(ModerationService.NumberChainType.ACCEPTED, chained.getType());
        assertEquals(2L, chained.getParsedValue());
        assertEquals(3L, service.getNumberChainNext(guildId));
    }
}
