package com.norule.musicbot.discord.bot.service.cache;

import java.util.List;

public interface PrivateRoomCacheRepository extends AutoCloseable {
    List<PrivateRoomCacheEntry> findAll();

    void upsert(PrivateRoomCacheEntry entry);

    void remove(long guildId, long channelId);

    @Override
    default void close() {
    }
}
