package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.ResolvedYouTubePlayback;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YouTubePlaybackResolver;
import java.util.Objects;

public final class CompanionPlaybackResolver implements YouTubePlaybackResolver {
    private final CompanionPlaybackClient client;

    public CompanionPlaybackResolver(CompanionPlaybackClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public java.util.concurrent.CompletableFuture<ResolvedYouTubePlayback> resolveAsync(String videoId, long guildId) {
        return client.resolveAsync(videoId, guildId);
    }

    @Override
    public ResolvedYouTubePlayback resolve(String videoId) throws YouTubePlaybackException {
        // No immediate transport retry: every new attempt must pass shared admission.
        return client.resolve(videoId);
    }
}
