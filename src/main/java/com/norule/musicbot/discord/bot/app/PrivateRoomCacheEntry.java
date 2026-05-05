package com.norule.musicbot.discord.bot.app;

public final class PrivateRoomCacheEntry {
    private final long guildId;
    private final long channelId;
    private final Long ownerId;
    private final long updatedAtMillis;

    public PrivateRoomCacheEntry(long guildId, long channelId, Long ownerId, long updatedAtMillis) {
        this.guildId = guildId;
        this.channelId = channelId;
        this.ownerId = ownerId;
        this.updatedAtMillis = updatedAtMillis;
    }

    public long getGuildId() {
        return guildId;
    }

    public long getChannelId() {
        return channelId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }
}
