package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.ResolvedYouTubePlayback;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YouTubePlaybackResolver;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CompanionAudioTrack extends DelegatedAudioTrack {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompanionAudioTrack.class);
    private static final Pattern HTTP_STATUS = Pattern.compile("(?<!\\d)([45]\\d{2})(?!\\d)");

    private final String videoId;
    private final InternalAudioTrack youtubeSourceTrack;
    private final YouTubePlaybackResolver resolver;
    private final HttpAudioSourceManager companionHttpSource;

    public CompanionAudioTrack(String videoId,
                               AudioTrack youtubeSourceTrack,
                               YouTubePlaybackResolver resolver,
                               HttpAudioSourceManager companionHttpSource) {
        super(youtubeSourceTrack.getInfo());
        if (!(youtubeSourceTrack instanceof InternalAudioTrack internalTrack)) {
            throw new IllegalArgumentException("YouTube source track must support local playback.");
        }
        this.videoId = videoId;
        this.youtubeSourceTrack = internalTrack;
        this.resolver = resolver;
        this.companionHttpSource = companionHttpSource;
        setUserData(youtubeSourceTrack.getUserData());
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        ResolvedYouTubePlayback resolved;
        try {
            resolved = resolver.resolve(videoId);
        } catch (YouTubePlaybackException failure) {
            throw friendly(failure);
        }
        if (!resolved.usesCompanionStream()) {
            logSelection(resolved);
            processDelegate(youtubeSourceTrack, executor);
            return;
        }

        logSelection(resolved);
        try {
            processCompanionStream(resolved, executor);
        } catch (Exception streamFailure) {
            YouTubePlaybackException classified = classifyStreamFailure(streamFailure);
            LOGGER.warn(
                    "[NoRule] YouTube playback failed: videoId={} backend=COMPANION category={}",
                    videoId,
                    classified.category()
            );
            ResolvedYouTubePlayback fallback;
            try {
                fallback = resolver.fallback(videoId, classified);
            } catch (YouTubePlaybackException finalFailure) {
                throw friendly(finalFailure);
            }
            if (fallback.backend() != com.norule.musicbot.domain.music.YouTubePlaybackBackend.YOUTUBE_SOURCE) {
                throw friendly(classified);
            }
            LOGGER.debug(
                    "[NoRule] YouTube playback selected: videoId={} backend=YOUTUBE_SOURCE fallback=true",
                    videoId
            );
            processDelegate(youtubeSourceTrack, executor);
        }
    }

    private void processCompanionStream(ResolvedYouTubePlayback resolved,
                                        LocalAudioTrackExecutor executor) throws Exception {
        AudioItem loaded = companionHttpSource.loadItem(
                (AudioPlayerManager) null,
                new AudioReference(resolved.streamUri().toString(), trackInfo.title)
        );
        if (!(loaded instanceof InternalAudioTrack httpTrack)) {
            throw new YouTubePlaybackException(
                    YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                    "Companion proxy did not expose a supported audio container."
            );
        }
        processDelegate(httpTrack, executor);
    }

    private YouTubePlaybackException classifyStreamFailure(Throwable failure) {
        for (Throwable current : throwableGraph(failure)) {
            if (current instanceof YouTubePlaybackException playbackException) {
                return playbackException;
            }
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return new YouTubePlaybackException(
                        YoutubeFailureCategory.COMPANION_TIMEOUT,
                        "Companion playback proxy timed out.",
                        null,
                        failure
                );
            }
            Integer status = httpStatus(current.getMessage());
            if (status != null && status >= 500) {
                return new YouTubePlaybackException(
                        YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                        "Companion playback proxy returned HTTP " + status + ".",
                        status,
                        failure
                );
            }
            if (current instanceof IOException) {
                return new YouTubePlaybackException(
                        YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                        "Companion playback proxy is unavailable.",
                        null,
                        failure
                );
            }
        }
        return new YouTubePlaybackException(
                YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                "Companion playback proxy stream is unavailable.",
                null,
                failure
        );
    }

    private void logSelection(ResolvedYouTubePlayback resolved) {
        LOGGER.debug(
                "[NoRule] YouTube playback selected: videoId={} backend={}",
                videoId,
                resolved.backend()
        );
    }

    private FriendlyException friendly(YouTubePlaybackException failure) {
        return new FriendlyException(
                "YouTube playback failed: backend=COMPANION category=" + failure.category(),
                FriendlyException.Severity.SUSPICIOUS,
                failure
        );
    }

    private Iterable<Throwable> throwableGraph(Throwable failure) {
        java.util.List<Throwable> failures = new java.util.ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            failures.add(current);
            current = current.getCause();
        }
        return failures;
    }

    private Integer httpStatus(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = HTTP_STATUS.matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new CompanionAudioTrack(
                videoId,
                youtubeSourceTrack.makeClone(),
                resolver,
                companionHttpSource
        );
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return youtubeSourceTrack.getSourceManager();
    }
}
