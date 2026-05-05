package com.norule.musicbot.discord.bot.app;

import java.util.List;

public final class MessageLogCacheEntry {
    private final long messageId;
    private final long channelId;
    private final String authorTag;
    private final String authorId;
    private final boolean authorIsBot;
    private final List<Long> authorRoleIds;
    private final String content;
    private final String attachments;
    private final long cachedAtMillis;

    public MessageLogCacheEntry(long messageId,
                                long channelId,
                                String authorTag,
                                String authorId,
                                boolean authorIsBot,
                                List<Long> authorRoleIds,
                                String content,
                                String attachments,
                                long cachedAtMillis) {
        this.messageId = messageId;
        this.channelId = channelId;
        this.authorTag = authorTag == null ? "" : authorTag;
        this.authorId = authorId == null ? "" : authorId;
        this.authorIsBot = authorIsBot;
        this.authorRoleIds = authorRoleIds == null ? List.of() : List.copyOf(authorRoleIds);
        this.content = content == null ? "" : content;
        this.attachments = attachments == null ? "" : attachments;
        this.cachedAtMillis = cachedAtMillis;
    }

    public long getMessageId() {
        return messageId;
    }

    public long getChannelId() {
        return channelId;
    }

    public String getAuthorTag() {
        return authorTag;
    }

    public String getAuthorId() {
        return authorId;
    }

    public boolean isAuthorIsBot() {
        return authorIsBot;
    }

    public List<Long> getAuthorRoleIds() {
        return authorRoleIds;
    }

    public String getContent() {
        return content;
    }

    public String getAttachments() {
        return attachments;
    }

    public long getCachedAtMillis() {
        return cachedAtMillis;
    }
}
