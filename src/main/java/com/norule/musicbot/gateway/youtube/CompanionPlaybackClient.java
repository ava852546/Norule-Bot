package com.norule.musicbot.gateway.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norule.musicbot.domain.music.ResolvedYouTubePlayback;
import com.norule.musicbot.domain.music.YouTubePlaybackBackend;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.regex.Pattern;

public final class CompanionPlaybackClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompanionPlaybackClient.class);
    private static final int MAX_RESPONSE_CHARACTERS = 8 * 1024 * 1024;
    private static final int MAX_REASON_INPUT_CHARACTERS = 2_048;
    private static final String GOOGLEVIDEO_SUFFIX = ".googlevideo.com";
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)Bearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|secret|po[_ -]?token|pot|sig|lsig|(?<![A-Za-z0-9_])n|spc|visitor[_ -]?data)"
                    + "\\s*[:=]\\s*[^\\s&,;}]+"
    );
    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://[^\\s]+");

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

        HttpResponseData response;
        try {
            response = send(request);
        } catch (YouTubePlaybackException failure) {
            LOGGER.warn(
                    "[NoRule] Companion player request failed: videoId={} stage=PLAYER_REQUEST path={} "
                            + "status={} category={} failureType={}",
                    videoId,
                    playerUri.getPath(),
                    failure.httpStatus(),
                    failure.category(),
                    failure.getCause() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getCause().getClass().getSimpleName()
            );
            throw failure;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            YoutubeFailureCategory category = playerRequestCategory(response.statusCode());
            String reason = safeResponseReason(response.body());
            LOGGER.warn(
                    "[NoRule] Companion player request failed: videoId={} stage=PLAYER_REQUEST path={} "
                            + "status={} contentType={} category={} reason={}",
                    videoId,
                    playerUri.getPath(),
                    response.statusCode(),
                    safeContentType(response.contentType()),
                    category,
                    reason
            );
            throw new YouTubePlaybackException(
                    category,
                    "Companion player request failed with HTTP " + response.statusCode() + ": " + reason,
                    response.statusCode(),
                    null
            );
        }
        LOGGER.debug(
                "[NoRule] Companion player request: videoId={} stage=PLAYER_REQUEST path={} status={} contentType={}",
                videoId,
                playerUri.getPath(),
                response.statusCode(),
                safeContentType(response.contentType())
        );
        String body = response.body() == null ? "" : response.body();
        if (body.length() > MAX_RESPONSE_CHARACTERS) {
            LOGGER.warn(
                    "[NoRule] Companion player response failed: videoId={} stage=PLAYER_RESPONSE "
                            + "category={} reason=response-too-large",
                    videoId,
                    YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE
            );
            throw streamUnavailable("Invidious Companion player response is too large.", response.statusCode(), null);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("playabilityStatus").path("status").asText("");
            if (!"OK".equalsIgnoreCase(status)) {
                String reason = root.path("playabilityStatus").path("reason").asText("stream unavailable");
                YoutubeFailureCategory category = playabilityCategory(status, reason);
                LOGGER.warn(
                        "[NoRule] Companion player response failed: videoId={} stage=PLAYER_RESPONSE "
                                + "playability={} category={} reason={}",
                        videoId,
                        safeReason(status),
                        category,
                        safeReason(reason)
                );
                throw new YouTubePlaybackException(
                        category,
                        "Companion playability status: " + safeReason(reason),
                        response.statusCode(),
                        null
                );
            }
            FormatSelection selection;
            try {
                selection = selectAudioFormat(root.path("streamingData").path("adaptiveFormats"));
            } catch (YouTubePlaybackException failure) {
                LOGGER.warn(
                        "[NoRule] Companion audio selection failed: videoId={} stage=FORMAT_SELECTION "
                                + "category={} reason={}",
                        videoId,
                        failure.category(),
                        safeReason(failure.getMessage())
                );
                throw failure;
            }
            LOGGER.debug(
                    "[NoRule] Companion player response: videoId={} stage=PLAYER_RESPONSE playability={} "
                            + "adaptiveFormats={} audioFormats={}",
                    videoId,
                    status,
                    selection.adaptiveFormats(),
                    selection.audioFormats()
            );
            CompanionFormat format = selection.selected();
            if (format == null) {
                LOGGER.warn(
                        "[NoRule] Companion audio selection failed: videoId={} stage=FORMAT_SELECTION "
                                + "adaptiveFormats={} audioFormats={} compatibleFormats={} category={}",
                        videoId,
                        selection.adaptiveFormats(),
                        selection.audioFormats(),
                        selection.compatibleFormats(),
                        YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE
                );
                throw streamUnavailable(
                        "Invidious Companion returned no LavaPlayer-compatible Opus or AAC audio stream.",
                        response.statusCode(),
                        null
                );
            }
            LOGGER.debug(
                    "[NoRule] Companion audio selected: videoId={} stage=FORMAT_SELECTION itag={} mime={} "
                            + "codec={} bitrate={} contentLength={}",
                    videoId,
                    format.itag(),
                    mimeBase(format.mimeType()),
                    format.codec(),
                    format.bitrate(),
                    format.contentLength()
            );
            Instant expiresAt;
            try {
                expiresAt = expiration(format.directUri());
            } catch (YouTubePlaybackException failure) {
                LOGGER.warn(
                        "[NoRule] Companion proxy URL failed: videoId={} stage=PROXY_URL category={} reason={}",
                        videoId,
                        failure.category(),
                        safeReason(failure.getMessage())
                );
                throw failure;
            }
            URI proxyUri = buildProxyUri(videoId, format.directUri());
            return new ResolvedYouTubePlayback(
                    videoId,
                    YouTubePlaybackBackend.COMPANION,
                    proxyUri,
                    format.mimeType(),
                    format.codec(),
                    format.itag(),
                    format.bitrate(),
                    format.contentLength(),
                    expiresAt,
                    null
            );
        } catch (YouTubePlaybackException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn(
                    "[NoRule] Companion player response failed: videoId={} stage=PLAYER_RESPONSE "
                            + "category={} failureType={}",
                    videoId,
                    YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                    failure.getClass().getSimpleName()
            );
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

    private FormatSelection selectAudioFormat(JsonNode formats) throws YouTubePlaybackException {
        if (!formats.isArray()) {
            return new FormatSelection(null, 0, 0, 0);
        }
        List<CompanionFormat> candidates = new ArrayList<>();
        int adaptiveFormats = 0;
        int audioFormats = 0;
        int compatibleFormats = 0;
        for (JsonNode format : formats) {
            adaptiveFormats++;
            String mimeType = format.path("mimeType").asText("");
            String directUrl = format.path("url").asText("");
            if (!mimeType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                continue;
            }
            audioFormats++;
            String codec = codecFromMimeType(mimeType);
            int codecPriority = codecPriority(codec);
            if (codecPriority == 0 || directUrl.isBlank()) {
                continue;
            }
            compatibleFormats++;
            URI directUri = validateGoogleVideoUri(directUrl);
            int itag = format.path("itag").canConvertToInt() ? format.path("itag").asInt() : 0;
            long bitrate = format.path("bitrate").canConvertToLong() ? format.path("bitrate").asLong() : 0L;
            Long contentLength = parseLong(format.path("contentLength").asText(null));
            candidates.add(new CompanionFormat(
                    directUri,
                    mimeType,
                    codec,
                    itag,
                    bitrate,
                    contentLength,
                    codecPriority
            ));
        }
        CompanionFormat selected = candidates.stream()
                .max(Comparator.comparingInt(CompanionFormat::codecPriority)
                        .thenComparingLong(CompanionFormat::bitrate))
                .orElse(null);
        return new FormatSelection(selected, adaptiveFormats, audioFormats, compatibleFormats);
    }

    private URI buildProxyUri(String videoId, URI directUri) throws YouTubePlaybackException {
        String rawQuery = directUri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            throw proxyUrlFailure(videoId, "Companion stream URL is missing its signed query.", null);
        }
        if (queryValue(rawQuery, "host") != null) {
            throw proxyUrlFailure(videoId, "Companion stream URL contains an unexpected host parameter.", null);
        }
        try {
            URI proxyBase = appendPath(companionBaseUri, "/videoplayback");
            String encodedHost = URLEncoder.encode(directUri.getHost(), StandardCharsets.UTF_8);
            URI proxyUri = URI.create(proxyBase.toASCIIString() + "?host=" + encodedHost + "&" + rawQuery);
            LOGGER.debug(
                    "[NoRule] Companion proxy URL: videoId={} stage=PROXY_URL host={} path={} originHost={}",
                    videoId,
                    proxyUri.getHost(),
                    proxyUri.getPath(),
                    directUri.getHost()
            );
            return proxyUri;
        } catch (Exception failure) {
            throw proxyUrlFailure(videoId, "Unable to construct the Companion playback proxy URL.", failure);
        }
    }

    private YouTubePlaybackException proxyUrlFailure(String videoId, String message, Throwable cause) {
        LOGGER.warn(
                "[NoRule] Companion proxy URL failed: videoId={} stage=PROXY_URL category={} reason={}",
                videoId,
                YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                safeReason(message)
        );
        return streamUnavailable(message, null, cause);
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

    private static YoutubeFailureCategory playerRequestCategory(int status) {
        if (status == 401 || status == 403) {
            return YoutubeFailureCategory.COMPANION_AUTH_FAILED;
        }
        if (status == 400 || status == 404 || status == 422) {
            return YoutubeFailureCategory.COMPANION_BAD_REQUEST;
        }
        if (status == 408) {
            return YoutubeFailureCategory.COMPANION_TIMEOUT;
        }
        if (status == 429 || status >= 500) {
            return YoutubeFailureCategory.COMPANION_UNAVAILABLE;
        }
        return YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE;
    }

    private static YoutubeFailureCategory playabilityCategory(String status, String reason) {
        String normalized = ((status == null ? "" : status) + " " + (reason == null ? "" : reason))
                .toLowerCase(Locale.ROOT);
        if (normalized.contains("private")) {
            return YoutubeFailureCategory.VIDEO_PRIVATE;
        }
        if (normalized.contains("age") || normalized.contains("confirm your age")) {
            return YoutubeFailureCategory.VIDEO_AGE_RESTRICTED;
        }
        if (normalized.contains("country") || normalized.contains("region")) {
            return YoutubeFailureCategory.REGION_RESTRICTED;
        }
        if (normalized.contains("login") || normalized.contains("sign in")) {
            return YoutubeFailureCategory.LOGIN_REQUIRED;
        }
        if (normalized.contains("bot")) {
            return YoutubeFailureCategory.BOT_DETECTED;
        }
        if (normalized.contains("unavailable") || normalized.contains("not available")) {
            return YoutubeFailureCategory.VIDEO_UNAVAILABLE;
        }
        return YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE;
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

    private String safeResponseReason(String body) {
        if (body == null || body.isBlank()) {
            return "response omitted";
        }
        String bounded = body.length() <= MAX_REASON_INPUT_CHARACTERS
                ? body
                : body.substring(0, MAX_REASON_INPUT_CHARACTERS);
        try {
            JsonNode root = objectMapper.readTree(bounded);
            for (String field : List.of("message", "reason", "detail", "error")) {
                JsonNode value = root.path(field);
                if (value.isTextual() && !value.asText().isBlank()) {
                    return safeReason(value.asText());
                }
            }
            return "structured error response";
        } catch (IOException ignored) {
            // A short plain-text error response can still provide a safe diagnostic after redaction.
        }
        String trimmed = bounded.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "invalid JSON error response";
        }
        return safeReason(bounded);
    }

    private String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        String sanitized = value;
        if (!secret.isBlank()) {
            sanitized = sanitized.replace(secret, "<redacted>");
        }
        sanitized = BEARER_SECRET.matcher(sanitized).replaceAll("Bearer <redacted>");
        sanitized = SENSITIVE_ASSIGNMENT.matcher(sanitized).replaceAll("$1=<redacted>");
        sanitized = HTTP_URL.matcher(sanitized).replaceAll("<redacted-url>");
        sanitized = sanitized.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
    }

    private static String safeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "-";
        }
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
    }

    private static String mimeBase(String mimeType) {
        return safeContentType(mimeType);
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
                // Plain-http Companion deployments may reject the JDK client's h2c upgrade request with HTTP 400.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return request -> {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResponseData(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.body()
            );
        };
    }

    public record HealthResult(boolean healthy, Integer httpStatus, String detail) {
    }

    interface HttpTransport {
        HttpResponseData send(HttpRequest request) throws IOException, InterruptedException;
    }

    record HttpResponseData(int statusCode, String contentType, String body) {
        HttpResponseData(int statusCode, String body) {
            this(statusCode, null, body);
        }
    }

    private record CompanionFormat(
            URI directUri,
            String mimeType,
            String codec,
            int itag,
            long bitrate,
            Long contentLength,
            int codecPriority
    ) {
    }

    private record FormatSelection(
            CompanionFormat selected,
            int adaptiveFormats,
            int audioFormats,
            int compatibleFormats
    ) {
    }
}
