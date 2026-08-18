package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.domain.MusicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class YouTubePlaybackPrecheckService {
    private static final Logger LOG = LoggerFactory.getLogger(YouTubePlaybackPrecheckService.class);
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final String ENV_LAVALINK_BASE_URL = "LAVALINK_BASE_URL";
    private static final String ENV_LAVALINK_PASSWORD = "LAVALINK_PASSWORD";

    private final Supplier<MusicConfig.Youtube.StrictPrecheck> configSupplier;
    private final StreamStatusClient streamStatusClient;
    private final Clock clock;
    private final Map<String, CachedYouTubePrecheckResult> cache = new ConcurrentHashMap<>();

    public YouTubePlaybackPrecheckService(Supplier<MusicConfig.Youtube.StrictPrecheck> configSupplier) {
        this(configSupplier, new HttpStreamStatusClient(), Clock.systemUTC());
    }

    public YouTubePlaybackPrecheckService(Supplier<MusicConfig.Youtube.StrictPrecheck> configSupplier,
                                          StreamStatusClient streamStatusClient,
                                          Clock clock) {
        this.configSupplier = configSupplier == null ? () -> MusicConfig.Youtube.StrictPrecheck.fromLegacy(null) : configSupplier;
        this.streamStatusClient = streamStatusClient == null ? new HttpStreamStatusClient() : streamStatusClient;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public YouTubePlaybackPrecheckResult check(String input) {
        Instant now = clock.instant();
        VideoCandidate candidate = findVideoCandidate(input);
        if (candidate.status != YouTubePlaybackPrecheckStatus.OK) {
            return result(candidate.status, candidate.videoId, now, null, candidate.reason, null);
        }

        MusicConfig.Youtube.StrictPrecheck config = config();
        String videoId = candidate.videoId;
        if (!config.isEnabled()) {
            return result(YouTubePlaybackPrecheckStatus.CONFIG_DISABLED, videoId, now, null, "strict precheck disabled", null);
        }

        CachedYouTubePrecheckResult cached = cache.get(videoId);
        if (cached != null && !cached.isExpired(now)) {
            LOG.debug("YouTube precheck cache hit videoId={} result={} httpStatus={}",
                    videoId, cached.status(), cached.httpStatus());
            return cached.toResult(videoId);
        }
        if (cached != null) {
            cache.remove(videoId, cached);
        }

        String baseUrl = firstNonBlank(System.getenv(ENV_LAVALINK_BASE_URL), config.getLavalinkBaseUrl());
        String password = firstNonBlank(System.getenv(ENV_LAVALINK_PASSWORD), config.getLavalinkPassword());
        if (baseUrl == null) {
            LOG.warn("YouTube precheck unavailable: missing Lavalink base URL");
            return result(YouTubePlaybackPrecheckStatus.LAVALINK_UNAVAILABLE, videoId, now, null, "missing Lavalink base URL", null);
        }

        LOG.debug("YouTube precheck cache miss videoId={}", videoId);
        try {
            Duration timeout = Duration.ofMillis(Math.max(1, config.getTimeoutMillis()));
            int httpStatus = streamStatusClient.fetchStreamStatus(baseUrl, password == null ? "" : password, videoId, timeout);
            YouTubePlaybackPrecheckResult classified = classify(videoId, now, config, httpStatus);
            cacheResult(videoId, classified);
            return classified;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("Lavalink YouTube precheck interrupted videoId={}", videoId);
            YouTubePlaybackPrecheckResult failure = temporaryResult(
                    YouTubePlaybackPrecheckStatus.LAVALINK_UNAVAILABLE, videoId, now, config, "interrupted", null);
            cacheResult(videoId, failure);
            return failure;
        } catch (IOException ex) {
            if (isTimeout(ex)) {
                LOG.warn("Lavalink YouTube precheck timeout videoId={}", videoId);
                YouTubePlaybackPrecheckResult failure = temporaryResult(
                        YouTubePlaybackPrecheckStatus.TIMEOUT, videoId, now, config, "timeout", null);
                cacheResult(videoId, failure);
                return failure;
            }
            LOG.warn("Lavalink YouTube precheck unavailable videoId={} reason={}", videoId, safeMessage(ex));
            YouTubePlaybackPrecheckResult failure = temporaryResult(
                    YouTubePlaybackPrecheckStatus.LAVALINK_UNAVAILABLE,
                    videoId,
                    now,
                    config,
                    safeMessage(ex),
                    null
            );
            cacheResult(videoId, failure);
            return failure;
        } catch (RuntimeException ex) {
            LOG.error("Unexpected YouTube precheck error videoId={}", videoId, ex);
            YouTubePlaybackPrecheckResult failure = temporaryResult(
                    YouTubePlaybackPrecheckStatus.UNKNOWN_ERROR,
                    videoId,
                    now,
                    config,
                    safeMessage(ex),
                    null
            );
            cacheResult(videoId, failure);
            return failure;
        }
    }

    public void recordPlaybackFailure(String input, YoutubeFailureReport failure) {
        VideoCandidate candidate = findVideoCandidate(input);
        if (candidate.status != YouTubePlaybackPrecheckStatus.OK || candidate.videoId == null) {
            return;
        }
        String videoId = candidate.videoId;
        cache.remove(videoId);
        MusicConfig.Youtube.StrictPrecheck config = config();
        if (!config.isEnabled() || failure == null) {
            return;
        }
        Instant checkedAt = clock.instant();
        YouTubePlaybackPrecheckStatus status = switch (failure.recoveryClass()) {
            case AUTH_MAY_HELP -> YouTubePlaybackPrecheckStatus.AUTH_REQUIRED;
            case PERMANENT -> YouTubePlaybackPrecheckStatus.PERMANENT_FAILURE;
            case RETRYABLE, CLIENT_FALLBACK_MAY_HELP, DECODER_FALLBACK_MAY_HELP,
                    CONFIGURATION_ERROR, UNKNOWN ->
                    YouTubePlaybackPrecheckStatus.TEMPORARY_FAILURE;
        };
        YouTubePlaybackPrecheckResult cachedFailure = status == YouTubePlaybackPrecheckStatus.PERMANENT_FAILURE
                ? permanentResult(videoId, checkedAt, config, failure.category().name(), failure.httpStatus())
                : temporaryResult(status, videoId, checkedAt, config, failure.category().name(), failure.httpStatus());
        cacheResult(videoId, cachedFailure);
        LOG.debug("YouTube playable cache invalidated after playback failure videoId={} category={} cachedAs={}",
                videoId, failure.category(), status);
    }

    public static boolean isValidVideoId(String value) {
        return value != null && VIDEO_ID_PATTERN.matcher(value.trim()).matches();
    }

    public void cleanupExpired(Instant now) {
        Instant cutoff = now == null ? clock.instant() : now;
        cache.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isExpired(cutoff));
    }

    private MusicConfig.Youtube.StrictPrecheck config() {
        MusicConfig.Youtube.StrictPrecheck config = configSupplier.get();
        return config == null ? MusicConfig.Youtube.StrictPrecheck.fromLegacy(null) : config;
    }

    private YouTubePlaybackPrecheckResult classify(String videoId,
                                                  Instant checkedAt,
                                                  MusicConfig.Youtube.StrictPrecheck config,
                                                  int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return playableResult(videoId, checkedAt, config, "stream endpoint returned OK", httpStatus);
        }
        if (httpStatus == 400 || httpStatus == 404) {
            return permanentResult(videoId, checkedAt, config, "stream endpoint rejected video", httpStatus);
        }
        if (httpStatus == 408) {
            LOG.warn("Lavalink YouTube precheck timeout response videoId={} httpStatus={}", videoId, httpStatus);
            return temporaryResult(YouTubePlaybackPrecheckStatus.TIMEOUT,
                    videoId, checkedAt, config, "stream endpoint timeout", httpStatus);
        }
        if (httpStatus == 401 || httpStatus == 403) {
            LOG.warn("Lavalink YouTube precheck requires authentication videoId={} httpStatus={}", videoId, httpStatus);
            return temporaryResult(YouTubePlaybackPrecheckStatus.AUTH_REQUIRED,
                    videoId, checkedAt, config, "stream endpoint requires authentication", httpStatus);
        }
        if (httpStatus == 429 || httpStatus >= 500) {
            LOG.warn("Lavalink YouTube precheck unavailable videoId={} httpStatus={}", videoId, httpStatus);
            return temporaryResult(YouTubePlaybackPrecheckStatus.TEMPORARY_FAILURE,
                    videoId, checkedAt, config, "stream endpoint temporarily unavailable", httpStatus);
        }
        LOG.warn("Unexpected Lavalink YouTube precheck response videoId={} httpStatus={}", videoId, httpStatus);
        return temporaryResult(YouTubePlaybackPrecheckStatus.UNKNOWN_ERROR,
                videoId, checkedAt, config, "unexpected stream endpoint response", httpStatus);
    }

    private YouTubePlaybackPrecheckResult playableResult(String videoId,
                                                         Instant checkedAt,
                                                         MusicConfig.Youtube.StrictPrecheck config,
                                                         String reason,
                                                         Integer httpStatus) {
        Instant expiresAt = checkedAt.plus(Duration.ofHours(config.getPlayableTtlHours()));
        return result(YouTubePlaybackPrecheckStatus.OK, videoId, checkedAt, expiresAt, reason, httpStatus);
    }

    private YouTubePlaybackPrecheckResult temporaryResult(YouTubePlaybackPrecheckStatus status,
                                                          String videoId,
                                                          Instant checkedAt,
                                                          MusicConfig.Youtube.StrictPrecheck config,
                                                          String reason,
                                                          Integer httpStatus) {
        Instant expiresAt = checkedAt.plus(Duration.ofMinutes(config.getTemporaryFailureTtlMinutes()));
        return result(status, videoId, checkedAt, expiresAt, reason, httpStatus);
    }

    private YouTubePlaybackPrecheckResult permanentResult(String videoId,
                                                          Instant checkedAt,
                                                          MusicConfig.Youtube.StrictPrecheck config,
                                                          String reason,
                                                          Integer httpStatus) {
        Instant expiresAt = checkedAt.plus(Duration.ofHours(config.getPermanentFailureTtlHours()));
        return result(YouTubePlaybackPrecheckStatus.PERMANENT_FAILURE,
                videoId, checkedAt, expiresAt, reason, httpStatus);
    }

    private void cacheResult(String videoId, YouTubePlaybackPrecheckResult result) {
        if (result == null || result.expiresAt() == null) {
            return;
        }
        cache.put(videoId, new CachedYouTubePrecheckResult(
                result.status(),
                result.checkedAt(),
                result.expiresAt(),
                result.reason(),
                result.httpStatus()
        ));
    }

    private YouTubePlaybackPrecheckResult result(YouTubePlaybackPrecheckStatus status,
                                                String videoId,
                                                Instant checkedAt,
                                                Instant expiresAt,
                                                String reason,
                                                Integer httpStatus) {
        return new YouTubePlaybackPrecheckResult(status, videoId, checkedAt, expiresAt, reason, httpStatus);
    }

    private VideoCandidate findVideoCandidate(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isBlank() || value.regionMatches(true, 0, "ytsearch:", 0, "ytsearch:".length())) {
            return VideoCandidate.skipped("not a single YouTube video");
        }
        if (VIDEO_ID_PATTERN.matcher(value).matches()) {
            return VideoCandidate.ok(value);
        }
        if (!looksLikeUrl(value)) {
            return VideoCandidate.skipped("not a YouTube URL or video ID");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            return VideoCandidate.skipped("invalid URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.contains("youtube.com") && !host.contains("youtu.be")) {
            return VideoCandidate.skipped("not a YouTube URL");
        }
        if (host.contains("youtu.be")) {
            return fromPathSegment(firstPathSegment(uri.getPath()));
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if ("/watch".equals(path)) {
            String videoId = queryValue(uri.getRawQuery(), "v");
            if (videoId == null || videoId.isBlank()) {
                return VideoCandidate.skipped("YouTube URL is not a single video");
            }
            return validateVideoId(videoId);
        }
        if (path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/")) {
            String[] parts = path.split("/");
            return parts.length >= 3 ? validateVideoId(parts[2]) : VideoCandidate.invalid("missing YouTube video ID");
        }
        return VideoCandidate.skipped("YouTube URL is not a single video");
    }

    private VideoCandidate fromPathSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return VideoCandidate.skipped("YouTube URL is not a single video");
        }
        return validateVideoId(segment);
    }

    private VideoCandidate validateVideoId(String videoId) {
        String value = videoId == null ? "" : videoId.trim();
        if (VIDEO_ID_PATTERN.matcher(value).matches()) {
            return VideoCandidate.ok(value);
        }
        return VideoCandidate.invalid("invalid YouTube video ID");
    }

    private String firstPathSegment(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String value = path.startsWith("/") ? path.substring(1) : path;
        int slash = value.indexOf('/');
        return slash >= 0 ? value.substring(0, slash) : value;
    }

    private String queryValue(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && key.equalsIgnoreCase(keyValue[0])) {
                return keyValue[1];
            }
        }
        return null;
    }

    private boolean looksLikeUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "-" : throwable.getClass().getSimpleName();
        }
        return throwable.getMessage().trim();
    }

    public interface StreamStatusClient {
        int fetchStreamStatus(String baseUrl, String password, String videoId, Duration timeout)
                throws IOException, InterruptedException;
    }

    public static final class HttpStreamStatusClient implements StreamStatusClient {
        private final HttpClient httpClient;

        public HttpStreamStatusClient() {
            this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
        }

        HttpStreamStatusClient(HttpClient httpClient) {
            this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        }

        @Override
        public int fetchStreamStatus(String baseUrl, String password, String videoId, Duration timeout)
                throws IOException, InterruptedException {
            String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            String encodedVideoId = URLEncoder.encode(videoId, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(normalizedBaseUrl + "/youtube/stream/" + encodedVideoId))
                    .timeout(timeout)
                    .GET();
            if (password != null && !password.isBlank()) {
                builder.header("Authorization", password);
            }
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = response.body();
            if (body != null) {
                body.close();
            }
            return response.statusCode();
        }
    }

    private record VideoCandidate(YouTubePlaybackPrecheckStatus status, String videoId, String reason) {
        static VideoCandidate ok(String videoId) {
            return new VideoCandidate(YouTubePlaybackPrecheckStatus.OK, videoId, "single YouTube video");
        }

        static VideoCandidate skipped(String reason) {
            return new VideoCandidate(YouTubePlaybackPrecheckStatus.SKIPPED, null, reason);
        }

        static VideoCandidate invalid(String reason) {
            return new VideoCandidate(YouTubePlaybackPrecheckStatus.INVALID_YOUTUBE_ID, null, reason);
        }
    }
}
