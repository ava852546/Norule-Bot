package com.norule.musicbot.discord.bot.app;

public interface MessageLogCacheRepository extends AutoCloseable {
    void upsert(MessageLogCacheEntry entry);

    MessageLogCacheEntry find(long messageId);

    MessageLogCacheEntry remove(long messageId);

    void pruneExpired(long cutoffMillis);

    @Override
    default void close() {
    }
}
