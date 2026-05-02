package com.norule.musicbot.domain.stats;

import java.util.List;

public class MessageStatsService {
    private final MessageStatsRepository repository;

    public MessageStatsService(MessageStatsRepository repository) {
        this.repository = repository;
    }

    public void trackMessage(long guildId, long userId, long messageId) {
        repository.incrementIfNewMessage(guildId, userId, messageId);
    }

    public long getUserMessageCount(long guildId, long userId) {
        return repository.getMessageCount(guildId, userId);
    }

    public List<UserMessageCount> getTopUsers(long guildId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(25, limit));
        return repository.getTopMessageCounts(guildId, normalizedLimit);
    }

    public void trackVoiceDuration(long guildId, long userId, long seconds) {
        repository.incrementVoiceSeconds(guildId, userId, seconds);
    }

    public long getUserVoiceSeconds(long guildId, long userId) {
        return repository.getVoiceSeconds(guildId, userId);
    }

    public List<UserVoiceTime> getTopVoiceUsers(long guildId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(25, limit));
        return repository.getTopVoiceSeconds(guildId, normalizedLimit);
    }
}

