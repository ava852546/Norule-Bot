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
    public ResolvedYouTubePlayback resolve(String videoId) throws YouTubePlaybackException {
        return client.resolve(videoId);
    }
}
