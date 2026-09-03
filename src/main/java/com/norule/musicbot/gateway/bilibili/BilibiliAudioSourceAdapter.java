package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.bilibili.BilibiliCircuitBreaker;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureClassifier;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureReport;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliMetadata;
import com.norule.musicbot.domain.music.bilibili.BilibiliMetadataCache;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestRateLimiter;
import com.norule.musicbot.domain.music.bilibili.BilibiliSingleFlight;
import com.norule.musicbot.domain.music.bilibili.BilibiliSourceLifecycle;
import com.norule.musicbot.domain.music.bilibili.BilibiliVideoIdentifier;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import dev.lavalink.bilibili.BilibiliAudioSourceManager;
import dev.lavalink.bilibili.BilibiliAudioTrack;
import dev.lavalink.bilibili.BilibiliHttpContextFilter;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.config.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class BilibiliAudioSourceAdapter implements AudioSourceManager, BilibiliSourceLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(BilibiliAudioSourceAdapter.class);
    private static final Duration SINGLE_FLIGHT_TIMEOUT = Duration.ofSeconds(10);
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 10_000;

    private final BilibiliAudioSourceManager delegate;
    private final PrimaryMetadataLoader primaryMetadataLoader;
    private final BilibiliPagelistMetadataResolver pagelistMetadataResolver;
    private final BilibiliCircuitBreaker circuitBreaker;
    private final BilibiliFailureClassifier failureClassifier = new BilibiliFailureClassifier();
    private final BilibiliRequestRateLimiter rateLimiter;
    private final BilibiliMetadataCache metadataCache;
    private final BilibiliSingleFlight<AudioItem> singleFlight = new BilibiliSingleFlight<>();
    private final HttpContextFilter vendorHttpFilter = new BilibiliHttpContextFilter();
    private volatile boolean enabled;
    private volatile String cookie = "";
    private volatile int failureWindowSeconds = 60;
    private volatile int cooldownSeconds = 300;

    public BilibiliAudioSourceAdapter(MusicConfig.Bilibili config) {
        this(config, null, null);
    }

    BilibiliAudioSourceAdapter(MusicConfig.Bilibili config,
                               PrimaryMetadataLoader primaryMetadataLoader,
                               BilibiliPagelistMetadataResolver pagelistMetadataResolver) {
        MusicConfig.Bilibili resolved = config == null
                ? MusicConfig.defaultValues().getBilibili()
                : config;
        MusicConfig.Bilibili.CircuitBreaker breakerConfig = resolved.getCircuitBreaker();
        MusicConfig.Bilibili.RateLimit rateLimitConfig = resolved.getRateLimit();
        MusicConfig.Bilibili.MetadataCache cacheConfig = resolved.getMetadataCache();
        this.circuitBreaker = new BilibiliCircuitBreaker(
                breakerConfig.isEnabled(),
                breakerConfig.getFailureThreshold(),
                Duration.ofSeconds(breakerConfig.getWindowSeconds()),
                Duration.ofSeconds(breakerConfig.getCooldownSeconds())
        );
        this.rateLimiter = new BilibiliRequestRateLimiter(
                rateLimitConfig.isEnabled(),
                rateLimitConfig.getRequestsPerSecond(),
                rateLimitConfig.getBurst()
        );
        this.metadataCache = new BilibiliMetadataCache(
                cacheConfig.isEnabled(),
                Duration.ofHours(cacheConfig.getTtlHours()),
                cacheConfig.getMaxEntries()
        );
        this.delegate = new BilibiliAudioSourceManager();
        HttpInterfaceManager httpInterfaceManager = configureHttpRuntime();
        this.primaryMetadataLoader = primaryMetadataLoader == null
                ? delegate::loadItem
                : primaryMetadataLoader;
        this.pagelistMetadataResolver = pagelistMetadataResolver == null
                ? new BilibiliPagelistMetadataResolver(httpInterfaceManager)
                : pagelistMetadataResolver;
        updateConfig(resolved);
    }

    @Override
    public String getSourceName() {
        return delegate.getSourceName();
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        String identifier = reference == null ? null : reference.identifier;
        if (!enabled || !BilibiliVideoIdentifier.isBilibiliInput(identifier)) {
            return null;
        }
        Optional<BilibiliVideoIdentifier.VideoRequest> videoRequest = BilibiliVideoIdentifier.from(identifier);
        if (circuitBreaker.state() == BilibiliCircuitBreaker.State.OPEN) {
            throw circuitOpenFailure(BilibiliFailureStage.METADATA);
        }
        if (videoRequest.isPresent()) {
            BilibiliVideoIdentifier.VideoRequest request = videoRequest.get();
            Optional<BilibiliMetadata> cached = metadataCache.get(request.bvid());
            if (cached.isPresent()) {
                return toAudioItem(cached.get(), request.page());
            }
            try {
                AudioItem loaded = singleFlight.execute(
                        request.singleFlightKey(),
                        SINGLE_FLIGHT_TIMEOUT,
                        () -> loadAndCache(manager, reference, request)
                );
                return cloneAudioItem(loaded);
            } catch (RuntimeException runtimeFailure) {
                throw runtimeFailure;
            } catch (Exception failure) {
                throw new FriendlyException(
                        "Bilibili metadata request failed",
                        FriendlyException.Severity.SUSPICIOUS,
                        failure
                );
            }
        }
        return loadGuarded(manager, reference);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return delegate.isTrackEncodable(track);
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws java.io.IOException {
        delegate.encodeTrack(track, output);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws java.io.IOException {
        return delegate.decodeTrack(trackInfo, input);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public void updateConfig(MusicConfig.Bilibili config) {
        MusicConfig.Bilibili resolved = config == null
                ? MusicConfig.defaultValues().getBilibili()
                : config;
        this.enabled = resolved.isEnabled();
        String nextCookie = resolved.getCookie() == null ? "" : resolved.getCookie().trim();
        boolean cookieBecameConfigured = cookie.isBlank() && !nextCookie.isBlank();
        this.cookie = nextCookie;

        MusicConfig.Bilibili.MetadataCache cache = resolved.getMetadataCache();
        metadataCache.updateConfig(cache.isEnabled(), Duration.ofHours(cache.getTtlHours()), cache.getMaxEntries());
        MusicConfig.Bilibili.RateLimit limit = resolved.getRateLimit();
        rateLimiter.updateConfig(limit.isEnabled(), limit.getRequestsPerSecond(), limit.getBurst());
        MusicConfig.Bilibili.CircuitBreaker breaker = resolved.getCircuitBreaker();
        this.failureWindowSeconds = breaker.getWindowSeconds();
        this.cooldownSeconds = breaker.getCooldownSeconds();
        circuitBreaker.updateConfig(
                breaker.isEnabled(),
                breaker.getFailureThreshold(),
                Duration.ofSeconds(breaker.getWindowSeconds()),
                Duration.ofSeconds(breaker.getCooldownSeconds())
        );
        if (cookieBecameConfigured) {
            LOGGER.info("[NoRule] Bilibili cookie authentication configured.");
        }
    }

    @Override
    public void setPlaylistPageCount(int pageCount) {
        delegate.setPlaylistPageCount(Math.max(1, pageCount));
    }

    @Override
    public String breakerState() {
        return circuitBreaker.state().name();
    }

    @Override
    public void cleanupExpiredMetadata() {
        metadataCache.cleanupExpired();
    }

    BilibiliMetadataCache.Statistics cacheStatistics() {
        return metadataCache.statistics();
    }

    int breakerFailureCount() {
        return circuitBreaker.failureCount();
    }

    int singleFlightParticipantCount() {
        return singleFlight.activeParticipantCount();
    }

    private AudioItem loadAndCache(AudioPlayerManager manager,
                                   AudioReference reference,
                                   BilibiliVideoIdentifier.VideoRequest request) {
        acquireMetadataPermission();
        try {
            AudioItem item = primaryMetadataLoader.load(manager, reference);
            if (request.page() == null) {
                toMetadata(item, request.bvid()).ifPresent(metadataCache::put);
            }
            logCircuitTransition(circuitBreaker.recordSuccess(), 0);
            return item;
        } catch (Throwable primaryFailure) {
            if (isPrimaryRiskControlFailure(primaryFailure)) {
                return loadPagelistFallback(request);
            }
            circuitBreaker.releaseHalfOpenProbe();
            throw asRuntime(primaryFailure, "Bilibili metadata request failed");
        }
    }

    private AudioItem loadGuarded(AudioPlayerManager manager, AudioReference reference) {
        acquireMetadataPermission();
        try {
            AudioItem item = primaryMetadataLoader.load(manager, reference);
            logCircuitTransition(circuitBreaker.recordSuccess(), 0);
            return item;
        } catch (Throwable failure) {
            circuitBreaker.releaseHalfOpenProbe();
            throw asRuntime(failure, "Bilibili metadata request failed");
        }
    }

    private void acquireMetadataPermission() {
        if (!circuitBreaker.tryAcquirePermission()) {
            throw circuitOpenFailure(BilibiliFailureStage.METADATA);
        }
        if (!rateLimiter.tryAcquire()) {
            circuitBreaker.releaseHalfOpenProbe();
            throw localRateLimitFailure();
        }
    }

    private AudioItem loadPagelistFallback(BilibiliVideoIdentifier.VideoRequest request) {
        if (!rateLimiter.tryAcquire()) {
            circuitBreaker.releaseHalfOpenProbe();
            throw localRateLimitFailure();
        }
        try {
            BilibiliMetadata metadata = pagelistMetadataResolver.resolve(request.bvid(), request.page());
            metadataCache.put(metadata);
            AudioItem item = toAudioItem(metadata, request.page());
            logCircuitTransition(circuitBreaker.recordSuccess(), 0);
            LOGGER.info(
                    "[NoRule] Bilibili metadata fallback succeeded: videoId={} primaryStatus=412 "
                            + "fallback=PAGELIST cid={} page={} degradedMetadata={}",
                    request.bvid(),
                    metadata.cid(),
                    metadata.selectedPage(),
                    metadata.degraded()
            );
            return item;
        } catch (Throwable fallbackFailure) {
            BilibiliFailureReport report = failureClassifier.classify(
                    fallbackFailure,
                    BilibiliFailureStage.METADATA
            );
            if (report.breakerFailure()) {
                logCircuitTransition(circuitBreaker.recordFailure(report.httpStatus()), report.httpStatus());
            } else {
                circuitBreaker.releaseHalfOpenProbe();
            }
            LOGGER.info(
                    "[NoRule] Bilibili request rejected: videoId={} stage=METADATA primaryStatus=412 "
                            + "fallback=PAGELIST fallbackStatus={} category={} breakerState={} retryable={}",
                    request.bvid(),
                    report.httpStatus(),
                    report.category(),
                    circuitBreaker.state(),
                    report.retryable()
            );
            throw asRuntime(fallbackFailure, "Bilibili pagelist metadata request failed");
        }
    }

    private boolean isPrimaryRiskControlFailure(Throwable failure) {
        BilibiliFailureReport report = failureClassifier.classify(failure, BilibiliFailureStage.METADATA);
        return report.category() == BilibiliFailureCategory.BILIBILI_RISK_CONTROL
                && report.httpStatus() == 412;
    }

    private BilibiliRequestException localRateLimitFailure() {
        return new BilibiliRequestException(
                BilibiliFailureCategory.BILIBILI_RATE_LIMITED,
                BilibiliFailureStage.METADATA,
                0,
                "Bilibili metadata request was throttled by the local rate limiter"
        );
    }

    private RuntimeException asRuntime(Throwable failure, String message) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new FriendlyException(message, FriendlyException.Severity.SUSPICIOUS, failure);
    }

    private BilibiliRequestException circuitOpenFailure(BilibiliFailureStage stage) {
        int status = circuitBreaker.lastFailureStatus();
        BilibiliFailureCategory category = status == 429
                ? BilibiliFailureCategory.BILIBILI_RATE_LIMITED
                : BilibiliFailureCategory.BILIBILI_RISK_CONTROL;
        return new BilibiliRequestException(
                category,
                stage,
                status,
                "Bilibili requests are temporarily paused by the circuit breaker"
        );
    }

    private HttpInterfaceManager configureHttpRuntime() {
        try {
            HttpInterfaceManager manager = findHttpInterfaceManager();
            manager.configureRequests(existing -> RequestConfig.copy(existing)
                    .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                    .setConnectionRequestTimeout(CONNECT_TIMEOUT_MILLIS)
                    .setSocketTimeout(REQUEST_TIMEOUT_MILLIS)
                    .build());
            manager.setHttpContextFilter(new GuardedHttpContextFilter());
            return manager;
        } catch (ReflectiveOperationException failure) {
            delegate.shutdown();
            throw new IllegalStateException(
                    "Bilibili HTTP runtime is incompatible with the configured bilibili-source version",
                    failure
            );
        }
    }

    private HttpInterfaceManager findHttpInterfaceManager() throws ReflectiveOperationException {
        for (Field field : BilibiliAudioSourceManager.class.getDeclaredFields()) {
            if (HttpInterfaceManager.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(delegate);
                if (value instanceof HttpInterfaceManager manager) {
                    return manager;
                }
            }
        }
        throw new NoSuchFieldException("HttpInterfaceManager");
    }

    private Optional<BilibiliMetadata> toMetadata(AudioItem item, String bvid) {
        if (item instanceof AudioTrack track) {
            BilibiliMetadata.Page page = toPage(track, 1);
            if (page == null) {
                return Optional.empty();
            }
            return Optional.of(new BilibiliMetadata(
                    bvid,
                    page.cid(),
                    page.title(),
                    page.author(),
                    page.durationMillis(),
                    page.thumbnail(),
                    page.webpageUrl(),
                    page.title(),
                    false,
                    false,
                    false,
                    1,
                    List.of(page)
            ));
        }
        if (!(item instanceof AudioPlaylist playlist) || playlist.getTracks().isEmpty()) {
            return Optional.empty();
        }
        List<BilibiliMetadata.Page> pages = new ArrayList<>();
        int selectedPage = 1;
        for (int index = 0; index < playlist.getTracks().size(); index++) {
            AudioTrack track = playlist.getTracks().get(index);
            BilibiliMetadata.Page page = toPage(track, index + 1);
            if (page == null) {
                return Optional.empty();
            }
            pages.add(page);
            if (track == playlist.getSelectedTrack()) {
                selectedPage = index + 1;
            }
        }
        BilibiliMetadata.Page first = pages.get(0);
        long duration = pages.stream().mapToLong(BilibiliMetadata.Page::durationMillis).sum();
        return Optional.of(new BilibiliMetadata(
                bvid,
                first.cid(),
                playlist.getName(),
                first.author(),
                duration,
                first.thumbnail(),
                canonicalVideoUrl(bvid),
                playlist.getName(),
                true,
                playlist.isSearchResult(),
                false,
                selectedPage,
                pages
        ));
    }

    private BilibiliMetadata.Page toPage(AudioTrack track, int fallbackPage) {
        if (!(track instanceof BilibiliAudioTrack bilibiliTrack) || track.getInfo() == null) {
            return null;
        }
        AudioTrackInfo info = track.getInfo();
        Integer requestedPage = BilibiliVideoIdentifier.from(info.uri)
                .map(BilibiliVideoIdentifier.VideoRequest::page)
                .orElse(null);
        int page = requestedPage == null ? fallbackPage : requestedPage;
        return new BilibiliMetadata.Page(
                page,
                bilibiliTrack.getCid(),
                info.title,
                info.author,
                info.length,
                info.artworkUrl,
                info.uri,
                info.identifier,
                info.isStream,
                info.isrc,
                bilibiliTrack.getType().name(),
                bilibiliTrack.getId()
        );
    }

    private AudioItem toAudioItem(BilibiliMetadata metadata, Integer requestedPage) {
        List<AudioTrack> tracks = metadata.pages().stream().map(this::toTrack).toList();
        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }
        if (requestedPage != null) {
            for (int index = 0; index < metadata.pages().size(); index++) {
                if (metadata.pages().get(index).number() == requestedPage) {
                    return tracks.get(index);
                }
            }
            return AudioReference.NO_TRACK;
        }
        if (!metadata.playlist()) {
            return tracks.get(0);
        }
        AudioTrack selected = null;
        Integer selectedPage = metadata.degraded()
                ? metadata.pages().stream()
                        .filter(page -> page.number() == 1)
                        .findFirst()
                        .orElse(metadata.pages().get(0))
                        .number()
                : metadata.selectedPage();
        if (selectedPage != null) {
            for (int index = 0; index < metadata.pages().size(); index++) {
                if (metadata.pages().get(index).number() == selectedPage) {
                    selected = tracks.get(index);
                    break;
                }
            }
        }
        return new BasicAudioPlaylist(metadata.playlistName(), tracks, selected, metadata.searchResult());
    }

    private AudioItem cloneAudioItem(AudioItem item) {
        if (item instanceof AudioTrack track) {
            return track.makeClone();
        }
        if (item instanceof AudioPlaylist playlist) {
            List<AudioTrack> tracks = playlist.getTracks().stream().map(AudioTrack::makeClone).toList();
            AudioTrack selected = null;
            if (playlist.getSelectedTrack() != null) {
                int selectedIndex = playlist.getTracks().indexOf(playlist.getSelectedTrack());
                if (selectedIndex >= 0 && selectedIndex < tracks.size()) {
                    selected = tracks.get(selectedIndex);
                }
            }
            return new BasicAudioPlaylist(playlist.getName(), tracks, selected, playlist.isSearchResult());
        }
        return item;
    }

    private AudioTrack toTrack(BilibiliMetadata.Page page) {
        AudioTrackInfo info = new AudioTrackInfo(
                page.title(),
                page.author(),
                page.durationMillis(),
                page.identifier(),
                page.stream(),
                page.webpageUrl(),
                page.thumbnail(),
                page.isrc()
        );
        BilibiliAudioTrack.TrackType type;
        try {
            type = BilibiliAudioTrack.TrackType.valueOf(page.trackType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            type = BilibiliAudioTrack.TrackType.VIDEO;
        }
        return new BilibiliAudioTrack(info, type, page.sourceId(), page.cid(), delegate);
    }

    private String canonicalVideoUrl(String bvid) {
        return "https://www.bilibili.com/video/" + bvid + "/";
    }

    private void recordHttpResponse(int status) {
        if (status == 412 || status == 429) {
            logCircuitTransition(circuitBreaker.recordFailure(status), status);
        }
    }

    boolean defersMetadataFailure(HttpUriRequest request) {
        URI uri = request == null ? null : request.getURI();
        String path = uri == null ? null : uri.getPath();
        return "/x/web-interface/view".equals(path) || "/x/player/pagelist".equals(path);
    }

    private void logCircuitTransition(BilibiliCircuitBreaker.Transition transition, int status) {
        if (transition == BilibiliCircuitBreaker.Transition.OPENED
                || transition == BilibiliCircuitBreaker.Transition.REOPENED) {
            LOGGER.info(
                    "[NoRule] Bilibili circuit breaker opened: reason=HTTP_{} failures={} "
                            + "windowSeconds={} cooldownSeconds={} state={} retryable=false",
                    status,
                    circuitBreaker.failureCount(),
                    failureWindowSeconds,
                    cooldownSeconds,
                    circuitBreaker.state()
            );
        } else if (transition == BilibiliCircuitBreaker.Transition.RECOVERED) {
            LOGGER.info("[NoRule] Bilibili circuit breaker recovered.");
        }
    }

    private boolean isControlPlaneRequest(HttpUriRequest request) {
        URI uri = request == null ? null : request.getURI();
        String host = uri == null ? null : uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return "bilibili.com".equals(normalized)
                || normalized.endsWith(".bilibili.com")
                || "b23.tv".equals(normalized)
                || normalized.endsWith(".b23.tv");
    }

    private final class GuardedHttpContextFilter implements HttpContextFilter {
        @Override
        public void onContextOpen(HttpClientContext context) {
            vendorHttpFilter.onContextOpen(context);
        }

        @Override
        public void onContextClose(HttpClientContext context) {
            vendorHttpFilter.onContextClose(context);
        }

        @Override
        public void onRequest(HttpClientContext context, HttpUriRequest request, boolean isRepetition) {
            vendorHttpFilter.onRequest(context, request, isRepetition);
            if (!isControlPlaneRequest(request)) {
                return;
            }
            String currentCookie = cookie;
            if (!currentCookie.isBlank()) {
                request.setHeader("Cookie", currentCookie);
            }
        }

        @Override
        public boolean onRequestResponse(HttpClientContext context,
                                         HttpUriRequest request,
                                         HttpResponse response) {
            boolean repeat = vendorHttpFilter.onRequestResponse(context, request, response);
            if (isControlPlaneRequest(request)
                    && !defersMetadataFailure(request)
                    && response != null
                    && response.getStatusLine() != null) {
                recordHttpResponse(response.getStatusLine().getStatusCode());
            }
            return repeat;
        }

        @Override
        public boolean onRequestException(HttpClientContext context,
                                          HttpUriRequest request,
                                          Throwable error) {
            boolean repeat = vendorHttpFilter.onRequestException(context, request, error);
            if (isControlPlaneRequest(request)) {
                circuitBreaker.releaseHalfOpenProbe();
            }
            return repeat;
        }
    }

    @FunctionalInterface
    interface PrimaryMetadataLoader {
        AudioItem load(AudioPlayerManager manager, AudioReference reference);
    }
}
