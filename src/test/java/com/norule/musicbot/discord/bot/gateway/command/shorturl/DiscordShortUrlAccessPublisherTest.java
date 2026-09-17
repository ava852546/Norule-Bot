package com.norule.musicbot.discord.bot.gateway.command.shorturl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordShortUrlAccessPublisherTest {
    @Test
    void logsOnlyOriginWithoutCredentialsPathQueryOrFragment() {
        assertEquals("https://example.com", DiscordShortUrlAccessPublisher.targetOrigin(
                "https://username:password@example.com/private-token?token=secret#fragment"));
        assertEquals("unknown", DiscordShortUrlAccessPublisher.targetOrigin("not a URL"));
    }
}
