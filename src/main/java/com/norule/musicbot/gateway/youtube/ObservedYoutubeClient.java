package com.norule.musicbot.gateway.youtube;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.format.TrackFormats;

import java.io.IOException;
import java.net.URI;

/** Delegates every capability unchanged and observes calls without adding requests. */
final class ObservedYoutubeClient implements Client {
    final Client delegate;

    ObservedYoutubeClient(Client delegate) { this.delegate = delegate; }

    @Override public String getIdentifier() { return delegate.getIdentifier(); }
    @Override public String getPlayerParams() { return delegate.getPlayerParams(); }
    @Override public ClientOptions getOptions() { return delegate.getOptions(); }
    @Override public boolean canHandleRequest(String identifier) {
        boolean capable = delegate.canHandleRequest(identifier);
        if (!capable) YoutubeClientDiagnostics.skipped(this);
        return capable;
    }
    @Override public boolean supportsFormatLoading() {
        boolean capable = delegate.supportsFormatLoading();
        if (!capable) YoutubeClientDiagnostics.skipped(this);
        return capable;
    }
    @Override public boolean isEmbedded() { return delegate.isEmbedded(); }
    @Override public boolean supportsOAuth() { return delegate.supportsOAuth(); }
    @Override public boolean requirePlayerScript() { return delegate.requirePlayerScript(); }
    @Override public void setPlaylistPageCount(int count) { delegate.setPlaylistPageCount(count); }
    @Override public URI transformPlaybackUri(URI original, URI resolved) {
        return delegate.transformPlaybackUri(original, resolved);
    }

    @Override public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String id)
            throws CannotBeLoaded, IOException {
        return observe(() -> delegate.loadFormats(source, http, id));
    }
    @Override public AudioItem loadVideo(YoutubeAudioSourceManager source, HttpInterface http, String id)
            throws CannotBeLoaded, IOException {
        return observe(() -> delegate.loadVideo(source, http, id));
    }
    @Override public AudioItem loadSearch(YoutubeAudioSourceManager source, HttpInterface http, String query)
            throws CannotBeLoaded, IOException {
        return observe(() -> delegate.loadSearch(source, http, query));
    }
    @Override public AudioItem loadSearchMusic(YoutubeAudioSourceManager source, HttpInterface http, String query)
            throws CannotBeLoaded, IOException {
        return observe(() -> delegate.loadSearchMusic(source, http, query));
    }
    @Override public AudioItem loadMix(YoutubeAudioSourceManager source, HttpInterface http, String mix, String video)
            throws CannotBeLoaded, IOException {
        return observe(() -> delegate.loadMix(source, http, mix, video));
    }
    @Override public AudioItem loadPlaylist(YoutubeAudioSourceManager source, HttpInterface http, String list, String video)
            throws CannotBeLoaded, IOException {
        return observe(() -> delegate.loadPlaylist(source, http, list, video));
    }

    private <T> T observe(YoutubeClientDiagnostics.Operation<T> operation) throws CannotBeLoaded, IOException {
        return YoutubeClientDiagnostics.observe(this, operation);
    }
}
