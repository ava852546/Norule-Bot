package com.norule.musicbot.discord.bot.app;

public final class DuplicateMessageCacheEntry {
    private final long guildId;
    private final long channelId;
    private final long userId;
    private final String contentHash;
    private final int count;
    private final long timestampMillis;

    public DuplicateMessageCacheEntry(long guildId,
                                      long channelId,
                                      long userId,
                                      String contentHash,
                                      int count,
                                      long timestampMillis) {
        this.guildId = guildId;
        this.channelId = channelId;
        this.userId = userId;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.count = Math.max(1, count);
        this.timestampMillis = timestampMillis;
    }

    public long getGuildId() {
        return guildId;
    }

    public long getChannelId() {
        return channelId;
    }

    public long getUserId() {
        return userId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getCount() {
        return count;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }
}
