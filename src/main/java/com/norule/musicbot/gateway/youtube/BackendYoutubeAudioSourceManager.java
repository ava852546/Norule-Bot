package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.YouTubePlaybackBackend;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.norule.musicbot.domain.music.TrackLoadContext;
import com.norule.musicbot.domain.music.YoutubeFailureClassifier;
import com.norule.musicbot.domain.music.YoutubeRecoveryClass;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.YoutubeAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Search/list metadata use youtube-source; direct Companion videos use its player API.
 * Every resulting track routes playback here,
 * including tracks resolved lazily inside LavaSrc's Spotify mirroring resolver. */
final class BackendYoutubeAudioSourceManager extends YoutubeAudioSourceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendYoutubeAudioSourceManager.class);
    private static final java.util.regex.Pattern SPOTIFY_TRACK = java.util.regex.Pattern.compile(
            "open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/([A-Za-z0-9]+)");
    private final YouTubePlaybackTrackFactory trackFactory;

    BackendYoutubeAudioSourceManager(YouTubePlaybackTrackFactory trackFactory, Client... clients) {
        super(clients);
        this.trackFactory = Objects.requireNonNull(trackFactory);
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        String identifier = reference.identifier;
        String search = identifier != null && (identifier.startsWith("ytsearch:") || identifier.startsWith("ytmsearch:"))
                ? identifier.replace('\r', ' ').replace('\n', ' ') : "-";
        LOGGER.debug("[NoRule] YouTube candidate discovery: stage=METADATA_DISCOVERY configuredBackend={} searchQuery={}",
                trackFactory.backend(), search.substring(0, Math.min(search.length(), 240)));
        try (var scope = PolicyCipherManager.metadataScope()) {
            return super.loadItem(manager, reference);
        } catch (RuntimeException failure) {
            var classifier = new YoutubeFailureClassifier();
            var report = classifier.classify(failure);
            var log = report.recoveryClass() == com.norule.musicbot.domain.music.YoutubeRecoveryClass.PERMANENT
                    ? LOGGER.atInfo() : LOGGER.atWarn();
            log.log("[NoRule] YouTube candidate discovery failed: configuredBackend={} provider={} "
                    + "stage=METADATA_DISCOVERY category={} httpStatus={} clients={} failureType={}",
                    trackFactory.backend(), classifier.provider(failure), report.category(), report.httpStatus(),
                    report.clientsSummary(), failure.getClass().getSimpleName());
            if (report.recoveryClass() == YoutubeRecoveryClass.PERMANENT
                    && isExpectedFailure(failure, classifier,
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))) {
                // AllClientsFailedException is always SUSPICIOUS and embeds stack traces
                // in its message. Keep it as the cause for classification, not console text.
                if (failure instanceof FriendlyException friendly
                        && !report.allClientsFailed()
                        && friendly.severity == FriendlyException.Severity.COMMON) {
                    throw friendly;
                }
                String message = failure instanceof FriendlyException && !report.allClientsFailed()
                        ? failure.getMessage() : "YouTube metadata is unavailable.";
                throw new FriendlyException(message, FriendlyException.Severity.COMMON, failure);
            }
            throw failure;
        }
    }

    private static boolean isExpectedFailure(Throwable failure, YoutubeFailureClassifier classifier,
                                             java.util.Set<Throwable> visited) {
        if (!visited.add(failure)) return false;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (!isExpectedFailure(suppressed, classifier, visited)) return false;
        }
        if (failure instanceof AllClientsFailedException aggregate) {
            return !aggregate.getClientExceptions().isEmpty()
                    && aggregate.getClientExceptions().stream()
                        .allMatch(client -> isExpectedFailure(client, classifier, visited));
        }
        // Only unwrap known transport wrappers. A BUG must not become COMMON just
        // because its message (or another client's failure) mentions a private video.
        if (failure instanceof FriendlyException || failure instanceof ClientException
                || failure instanceof YouTubePlaybackException || failure.getClass() == RuntimeException.class) {
            if (failure.getCause() != null) {
                return isExpectedFailure(failure.getCause(), classifier, visited);
            }
            return !(failure.getClass() == RuntimeException.class)
                    && classifier.classify(failure).recoveryClass() == YoutubeRecoveryClass.PERMANENT;
        }
        return failure instanceof java.io.IOException
                && classifier.classify(failure).recoveryClass() == YoutubeRecoveryClass.PERMANENT;
    }

    @Override
    protected Router routeFromVideoId(com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface http,
                                      String videoId, dev.lavalink.youtube.UrlTools.UrlInfo url) {
        // Leave playlist/mix selection to the upstream router. Search and list metadata
        // do not need a player script; each selected track still uses the selected backend.
        if (trackFactory instanceof CompanionYouTubePlaybackTrackFactory companion
                && videoId != null && videoId.matches("[A-Za-z0-9_-]{11}")
                && isSingleVideo(url)) {
            try {
                // Resolve once, before upstream's per-client loop. A Companion failure
                // must not retry this same request for every youtube-source client.
                AudioTrack track = buildAudioTrack(companion.loadMetadata(videoId));
                return ignored -> track;
            } catch (com.norule.musicbot.domain.music.YouTubePlaybackException failure) {
                if (!companion.allowsSourceFallback(videoId, failure)) {
                    throw new com.sedmelluq.discord.lavaplayer.tools.FriendlyException(
                        "YouTube metadata is unavailable.",
                        new com.norule.musicbot.domain.music.YoutubeFailureClassifier().classify(failure).recoveryClass()
                                == com.norule.musicbot.domain.music.YoutubeRecoveryClass.PERMANENT
                                ? com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON
                                : com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS, failure);
                }
                LOGGER.info("event=YOUTUBE_METADATA_FALLBACK videoId={} primaryFailure={} actualBackend=YOUTUBE_SOURCE",
                        videoId, failure.category());
            }
        }
        Router route = super.routeFromVideoId(http, videoId, url);
        if (route == Router.none || !isSingleVideo(url)) {
            return route;
        }
        return client -> {
            // Capability, not a hardcoded client name: this upstream video path calls
            // getCachedPlayerScript before requesting metadata when this flag is true.
            if (client.requirePlayerScript() && getCipherManager() instanceof PolicyCipherManager guard) {
                guard.requirePlayerScriptAllowed();
            }
            return route.route(client);
        };
    }

    private static boolean isSingleVideo(dev.lavalink.youtube.UrlTools.UrlInfo url) {
        if (url == null || !url.parameters.containsKey("list")) return true;
        String list = url.parameters.get("list");
        // youtube-source 1.18.2 treats these personal-list URLs as a single video.
        return list.startsWith("LL") || list.startsWith("WL") || list.startsWith("LM");
    }

    @Override
    public YoutubeAudioTrack buildAudioTrack(AudioTrackInfo info) {
        return new BackendTrack(super.buildAudioTrack(info));
    }

    @Override
    public Client[] getClients() {
        Client[] configured = super.getClients();
        if (!PolicyCipherManager.isStreamScope()
                || !(getCipherManager() instanceof PolicyCipherManager guard) || guard.isAllowed()) {
            return configured;
        }
        Client[] capable = java.util.Arrays.stream(configured)
                .filter(client -> client.supportsFormatLoading() && !client.requirePlayerScript())
                .toArray(Client[]::new);
        if (capable.length == 0) {
            throw new com.norule.musicbot.domain.music.CipherDisabledException();
        }
        return capable;
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo info, java.io.DataInput input) {
        return buildAudioTrack(info);
    }

    final class BackendTrack extends YoutubeAudioTrack {
        private final YoutubeAudioTrack sourceTrack;

        BackendTrack(YoutubeAudioTrack sourceTrack) {
            super(sourceTrack.getInfo(), BackendYoutubeAudioSourceManager.this);
            this.sourceTrack = sourceTrack;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) throws Exception {
            AudioTrack selected = trackFactory.prepare(trackInfo.identifier, sourceTrack);
            YouTubePlaybackBackend actual = selected instanceof CompanionAudioTrack
                    ? YouTubePlaybackBackend.COMPANION : YouTubePlaybackBackend.YOUTUBE_SOURCE;
            TrackLoadContext context = getUserData() instanceof TrackLoadContext value ? value : null;
            String source = context == null ? "youtube" : context.sourceName();
            String sourceTrackId = "-";
            if (context != null) {
                var matcher = SPOTIFY_TRACK.matcher(context.resolvedIdentifier());
                if (matcher.find()) {
                    sourceTrackId = matcher.group(1);
                }
            }
            LOGGER.debug("[NoRule] YouTube playback routing: source={} sourceTrackId={} resolvedYoutubeVideoId={} "
                            + "configuredBackend={} actualBackend={} stage=PLAYBACK_EXTRACTION",
                    source, sourceTrackId, trackInfo.identifier, trackFactory.backend(), actual);
            if (actual != trackFactory.backend()) {
                LOGGER.error("[NoRule] Playback backend violation: configured={} attempted={} videoId={}",
                        trackFactory.backend(), actual, trackInfo.identifier);
                throw new IllegalStateException("Playback backend violation");
            }
            selected.setUserData(getUserData());
            try (var scope = PolicyCipherManager.streamScope()) {
                processDelegate((InternalAudioTrack) selected, executor);
            } catch (Exception failure) {
                LOGGER.debug("[NoRule] YouTube playback attempt ended: source={} sourceTrackId={} videoId={} "
                                + "configuredBackend={} failureType={}",
                        source, sourceTrackId, trackInfo.identifier, trackFactory.backend(), failure.getClass().getSimpleName());
                throw failure;
            }
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return buildAudioTrack(trackInfo);
        }
    }
}
