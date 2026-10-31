package com.norule.musicbot.discord.bot.infra;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Aggregate only JDA's repeated global warning; preserve all other library diagnostics. */
public final class DiscordGlobalRateLimitLogFilter extends Filter<ILoggingEvent> {
    private final AtomicLong nextWarning = new AtomicLong(System.nanoTime());

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!event.getLoggerName().startsWith("net.dv8tion.jda.")
                || !event.getFormattedMessage().startsWith("Encountered global rate limit!")) {
            return FilterReply.NEUTRAL;
        }
        long now = System.nanoTime();
        long next = nextWarning.get();
        return now >= next && nextWarning.compareAndSet(next, now + TimeUnit.SECONDS.toNanos(10))
                ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
