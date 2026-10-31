package com.norule.musicbot.discord.bot.gateway;

import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import com.norule.musicbot.discord.bot.infra.DiscordGlobalRateLimitLogFilter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordGlobalRateLimitLogFilterTest {
    @Test
    void repeatedJdaGlobalWarningsAreBoundedAndOtherDiagnosticsRemain() {
        var filter = new DiscordGlobalRateLimitLogFilter();
        var event = new LoggingEvent();
        event.setLoggerName("net.dv8tion.jda.internal.requests.SequentialRestRateLimiter");
        event.setMessage("Encountered global rate limit! Retry-After: 1000");
        assertEquals(FilterReply.NEUTRAL, filter.decide(event));
        for (int i = 0; i < 500; i++) assertEquals(FilterReply.DENY, filter.decide(event));
        var other = new LoggingEvent();
        other.setLoggerName("net.dv8tion.jda.internal.requests.Requester");
        other.setMessage("Unexpected REST failure");
        assertEquals(FilterReply.NEUTRAL, filter.decide(other));
    }
}
