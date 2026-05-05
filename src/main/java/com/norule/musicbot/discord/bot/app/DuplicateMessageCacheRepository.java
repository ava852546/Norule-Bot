package com.norule.musicbot.discord.bot.app;

public interface DuplicateMessageCacheRepository extends AutoCloseable {
    DuplicateMessageCacheEntry find(long guildId, long channelId, long userId, String contentHash);

    void upsert(DuplicateMessageCacheEntry entry);

    void pruneExpired(long cutoffMillis);

    @Override
    default void close() {
    }
}
