package com.norule.musicbot.domain.stats;

import java.util.List;

public interface MessageStatsRepository {
    boolean incrementIfNewMessage(long guildId, long userId, long messageId);

    long getMessageCount(long guildId, long userId);

    List<UserMessageCount> getTopMessageCounts(long guildId, int limit);

    void incrementVoiceSeconds(long guildId, long userId, long seconds);

    long getVoiceSeconds(long guildId, long userId);

    List<UserVoiceTime> getTopVoiceSeconds(long guildId, int limit);
}

