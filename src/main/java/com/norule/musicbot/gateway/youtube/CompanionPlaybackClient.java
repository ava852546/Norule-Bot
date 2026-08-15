package com.norule.musicbot.gateway.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norule.musicbot.domain.music.ResolvedYouTubePlayback;
import com.norule.musicbot.domain.music.YouTubePlaybackBackend;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CompanionPlaybackClient {
    private static final int MAX_RESPONSE_CHARACTERS = 8 * 1024 * 1024;
    private static final String GOOGLEVIDEO_SUFFIX = ".googlevideo.com";

    private final URI companionBaseUri;
    private final URI playerUri;
    private final URI healthUri;
    private final String secret;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final HttpTransport transport;

    public CompanionPlaybackClient(String configuredUrl,
                                   String secret,
                                   int connectTimeoutMillis,
                                   int requestTimeoutMillis) {
        this(
                configuredUrl,
                secret,
                requestTimeoutMillis,
                new ObjectMapper(),
                defaultTransport(connectTimeoutMillis)
        );
    }

    CompanionPlaybackClient(String configuredUrl,
                            String secret,
                            int requestTimeoutMillis,
                            ObjectMapper objectMapper,
                            HttpTransport transport) {
        this.companionBaseUri = normalizeCompanionBaseUri(configuredUrl);
        this.playerUri = appendPath(companionBaseUri, "/youtubei/v1/player");
        this.healthUri = originUri(companionBaseUri, "/healthz");
        this.secret = secret == null ? "" : secret;
        this.requestTimeout = Duration.ofMillis(Math.max(1, requestTimeoutMillis));
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.transport = transport;
    }

    public ResolvedYouTubePlayback resolve(String videoId) throws YouTubePlaybackException {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw streamUnavailable("Invalid YouTube video ID.", null, null);
        }
        HttpRequest request = HttpRequest.newBuilder(playerUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + secret)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"videoId\":\"" + videoId + "\"}",
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponseData response = send(request);
        if (response.statusCode() >= 500) {
            throw new YouTubePlaybackException(
                    YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                    "Invidious Companion returned HTTP " + response.statusCode() + ".",
                    response.statusCode(),
                    null
            );
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw streamUnavailable(
                    "Invidious Companion rejected the player request with HTTP " + response.statusCode() + ".",
                    response.statusCode(),
                    null
            );
        }
        String body = response.body() == null ? "" : response.body();
        if (body.length() > MAX_RESPONSE_CHARACTERS) {
            throw streamUnavailable("Invidious Companion player response is too large.", response.statusCode(), null);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("playabilityStatus").path("status").asText("");
            if (!"OK".equalsIgnoreCase(status)) {
                String reason = root.path("playabilityStatus").path("reason").asText("stream unavailable");
                throw streamUnavailable("Invidious Companion playability status: " + safeReason(reason), null, null);
            }
            CompanionFormat format = selectAudioFormat(root.path("streamingData").path("adaptiveFormats"));
            URI proxyUri = buildProxyUri(format.directUri());
            return new ResolvedYouTubePlayback(
                    videoId,
                    YouTubePlaybackBackend.COMPANION,
                    proxyUri,
                    format.mimeType(),
                    format.codec(),
                    format.bitrate(),
                    format.contentLength(),
                    expiration(format.directUri())
            );
        } catch (YouTubePlaybackException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw streamUnavailable("Invalid Invidious Companion player response.", null, failure);
        }
    }

    public HealthResult healthCheck() {
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponseData response = transport.send(request);
            boolean healthy = response.statusCode() >= 200 && response.statusCode() < 300
                    && "OK".equalsIgnoreCase(response.body() == null ? "" : response.body().trim());
            return new HealthResult(healthy, response.statusCode(), healthy ? "OK" : "unexpected response");
        } catch (HttpTimeoutException timeout) {
            return new HealthResult(false, null, "timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new HealthResult(false, null, "interrupted");
        } catch (IOException | RuntimeException failure) {
            return new HealthResult(false, null, safeReason(failure.getMessage()));
        }
    }

    public URI companionBaseUri() {
        return companionBaseUri;
    }

    public static boolean isValidSecret(String value) {
        return value != null && value.matches("[A-Za-z0-9]{16}");
    }

    private HttpResponseData send(HttpRequest request) throws YouTubePlaybackException {
        try {
            return transport.send(request);
        } catch (HttpTimeoutException timeout) {
            throw new YouTubePlaybackException(
                    YoutubeFailureCategory.COMPANION_TIMEOUT,
                    "Invidious Companion request timed out.",
                    null,
                    timeout
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new YouTubePlaybackException(
                    YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                    "Invidious Companion request was interrupted.",
                    null,
                    interrupted
            );
        } catch (IOException | RuntimeException failure) {
            throw new YouTubePlaybackException(
                    YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                    "Invidious Companion is unavailable.",
                    null,
                    failure
            );
        }
    }

    private CompanionFormat selectAudioFormat(JsonNode formats) throws YouTubePlaybackException {
        if (!formats.isArray()) {
            throw streamUnavailable("Invidious Companion returned no adaptive audio formats.", null, null);
        }
        List<CompanionFormat> candidates = new ArrayList<>();
        for (JsonNode format : formats) {
            String mimeType = format.path("mimeType").asText("");
            String directUrl = format.path("url").asText("");
            if (!mimeType.toLowerCase(Locale.ROOT).startsWith("audio/") || directUrl.isBlank()) {
                continue;
            }
            String codec = codecFromMimeType(mimeType);
            int codecPriority = codecPriority(codec);
            if (codecPriority == 0) {
                continue;
            }
            URI directUri = validateGoogleVideoUri(directUrl);
            long bitrate = format.path("bitrate").canConvertToLong() ? format.path("bitrate").asLong() : 0L;
            Long contentLength = parseLong(format.path("contentLength").asText(null));
            candidates.add(new CompanionFormat(
                    directUri,
                    mimeType,
                    codec,
                    bitrate,
                    contentLength,
                    codecPriority
            ));
        }
        return candidates.stream()
                .max(Comparator.comparingInt(CompanionFormat::codecPriority)
                        .thenComparingLong(CompanionFormat::bitrate))
                .orElseThrow(() -> streamUnavailable(
                        "Invidious Companion returned no LavaPlayer-compatible Opus or AAC audio stream.",
                        null,
                        null
                ));
    }

    private URI buildProxyUri(URI directUri) throws YouTubePlaybackException {
        String rawQuery = directUri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            throw streamUnavailable("Companion stream URL is missing its signed query.", null, null);
        }
        if (queryValue(rawQuery, "host") != null) {
            throw streamUnavailable("Companion stream URL contains an unexpected host parameter.", null, null);
        }
        try {
            URI proxyBase = appendPath(companionBaseUri, "/videoplayback");
            String encodedHost = URLEncoder.encode(directUri.getHost(), StandardCharsets.UTF_8);
            return URI.create(proxyBase.toASCIIString() + "?host=" + encodedHost + "&" + rawQuery);
        } catch (Exception failure) {
            throw streamUnavailable("Unable to construct the Companion playback proxy URL.", null, failure);
        }
    }

    private URI validateGoogleVideoUri(String value) throws YouTubePlaybackException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean validHost = host.endsWith(GOOGLEVIDEO_SUFFIX) && host.length() > GOOGLEVIDEO_SUFFIX.length();
            boolean validPort = uri.getPort() == -1 || uri.getPort() == 443;
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || !validHost
                    || !validPort) {
                throw streamUnavailable("Unsafe Companion stream origin was rejected.", null, null);
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            throw streamUnavailable("Invalid Companion stream URL.", null, failure);
        }
    }

    private Instant expiration(URI directUri) throws YouTubePlaybackException {
        String expire = queryValue(directUri.getRawQuery(), "expire");
        Long epochSeconds = parseLong(expire);
        if (epochSeconds == null) {
            throw streamUnavailable("Companion stream URL has no valid expiration.", null, null);
        }
        Instant expiresAt = Instant.ofEpochSecond(epochSeconds);
        if (!expiresAt.isAfter(Instant.now())) {
            throw streamUnavailable("Companion stream URL is already expired.", null, null);
        }
        return expiresAt;
    }

    private static URI normalizeCompanionBaseUri(String configuredUrl) {
        URI uri = URI.create(configuredUrl == null ? "" : configuredUrl.trim());
        String scheme = uri.getScheme();
        if (scheme == null
                || (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Companion URL must be an http(s) origin or base URL.");
        }
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "/companion";
        } else {
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Companion URL.", failure);
        }
    }

    private static URI appendPath(URI baseUri, String suffix) {
        try {
            return new URI(
                    baseUri.getScheme(),
                    null,
                    baseUri.getHost(),
                    baseUri.getPort(),
                    appendPathValue(baseUri.getPath(), suffix),
                    null,
                    null
            );
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Companion endpoint.", failure);
        }
    }

    private static URI originUri(URI baseUri, String path) {
        try {
            return new URI(baseUri.getScheme(), null, baseUri.getHost(), baseUri.getPort(), path, null, null);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Companion health endpoint.", failure);
        }
    }

    private static String appendPathValue(String basePath, String suffix) {
        String normalized = basePath == null || basePath.isBlank() ? "" : basePath;
        return normalized + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    private static String codecFromMimeType(String mimeType) {
        int codecsIndex = mimeType.toLowerCase(Locale.ROOT).indexOf("codecs=");
        if (codecsIndex < 0) {
            return "";
        }
        String codec = mimeType.substring(codecsIndex + "codecs=".length()).trim();
        if (codec.startsWith("\"") && codec.endsWith("\"") && codec.length() >= 2) {
            codec = codec.substring(1, codec.length() - 1);
        }
        return codec;
    }

    private static int codecPriority(String codec) {
        String normalized = codec == null ? "" : codec.toLowerCase(Locale.ROOT);
        if (normalized.contains("opus")) {
            return 2;
        }
        if (normalized.contains("mp4a") || normalized.contains("aac")) {
            return 1;
        }
        return 0;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String queryValue(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            if (name.equals(key)) {
                return separator < 0 ? "" : part.substring(separator + 1);
            }
        }
        return null;
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
    }

    private static YouTubePlaybackException streamUnavailable(String message,
                                                              Integer status,
                                                              Throwable cause) {
        return new YouTubePlaybackException(
                YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                message,
                status,
                cause
        );
    }

    private static HttpTransport defaultTransport(int connectTimeoutMillis) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMillis)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return request -> {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResponseData(response.statusCode(), response.body());
        };
    }

    public record HealthResult(boolean healthy, Integer httpStatus, String detail) {
    }

    interface HttpTransport {
        HttpResponseData send(HttpRequest request) throws IOException, InterruptedException;
    }

    record HttpResponseData(int statusCode, String body) {
    }

    private record CompanionFormat(
            URI directUri,
            String mimeType,
            String codec,
            long bitrate,
            Long contentLength,
            int codecPriority
    ) {
    }
}
