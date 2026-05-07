package com.norule.musicbot.discord.bot.app;

public interface MessageLogCacheRepository extends AutoCloseable {
    void upsert(MessageLogCacheEntry entry);

    MessageLogCacheEntry find(long messageId);

    MessageLogCacheEntry remove(long messageId);

    int pruneExpired(long cutoffMillis);

    @Override
    default void close() {
    }
}
