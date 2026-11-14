package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.CipherPolicy;
import com.norule.musicbot.domain.music.YoutubeFailureClassifier;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSource;
import dev.lavalink.youtube.cipher.LocalSignatureCipherManager;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Developer-only test-classpath CLI. Never boots Main, Discord, configuration or SQLite. */
public final class YouTubeCipherDiagnostic {
    static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    final Map<String, Object> report = new LinkedHashMap<>();
    final CipherDiagnosticTrace trace = new CipherDiagnosticTrace();
    private final FormatProbe probe = new FormatProbe();
    private JsonBrowser response;

    YouTubeCipherDiagnostic(String videoId) {
        if (!videoId.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Expected one video ID");
        report.put("videoId", videoId);
        report.put("configuredBackend", "YOUTUBE_SOURCE");
        report.put("youtubeSourceVersion", YoutubeSource.VERSION);
        report.put("client", "WEB");
        for (String field : List.of("playerHttpStatus", "playability", "reasonCategory", "streamingData",
                "adaptiveFormatsCount", "audioFormatsCount", "usableFormatsCount", "supportedCodec",
                "directAudioUrl", "signatureCipherPresent", "nPresent", "sabrPresent", "sabrOnly")) {
            report.put(field, "UNKNOWN");
        }
        report.put("streamAccess", "NOT_TESTED");
        report.put("decode", "NOT_TESTED");
        report.put("playback", "NOT_TESTED");
        report.put("failureStage", "UNKNOWN");
        report.put("failureCategory", "UNKNOWN");
        report.put("conclusion", "INCONCLUSIVE");
    }

    TrackFormats inspect(JsonBrowser json) {
        response = json;
        String status = json.get("playabilityStatus").get("status").text();
        // Output is an allowlist: raw reason, visitor data, payload and URL never leave this object.
        report.put("playability", List.of("OK", "LOGIN_REQUIRED", "UNPLAYABLE", "ERROR", "LIVE_STREAM_OFFLINE")
                .contains(status == null ? "" : status) ? status : "UNKNOWN");
        var reason = new YoutubeFailureClassifier().classify(
                new IllegalStateException(json.get("playabilityStatus").get("reason").safeText()));
        report.put("reasonCategory", reason.category());
        report.put("playabilityReason", json.get("playabilityStatus").get("reason").isNull() ? "UNKNOWN" : "REDACTED");
        if ("LOGIN_REQUIRED".equals(status) || reason.category().name().equals("BOT_DETECTED")) {
            fail("PLAYER_API", reason.category().name().equals("BOT_DETECTED") ? "BOT_DETECTED" : "LOGIN_REQUIRED",
                    "YOUTUBE_ACCESS_RESTRICTED");
        }
        JsonBrowser data = json.get("streamingData");
        report.put("streamingData", !data.isNull());
        List<JsonBrowser> adaptive = new ArrayList<>(data.get("adaptiveFormats").values());
        report.put("adaptiveFormatsCount", data.get("adaptiveFormats").isNull() ? "UNKNOWN" : adaptive.size());
        List<JsonBrowser> all = new ArrayList<>(adaptive);
        all.addAll(data.get("formats").values());
        int audio = 0, compatible = 0, malformed = 0;
        boolean direct = false, cipher = false, n = false;
        List<StreamFormat> formats = new ArrayList<>();
        for (JsonBrowser format : all) {
            String mime = format.get("mimeType").safeText();
            dev.lavalink.youtube.track.format.FormatInfo codec = null;
            try { codec = dev.lavalink.youtube.track.format.FormatInfo.get(org.apache.http.entity.ContentType.parse(mime)); }
            catch (IllegalArgumentException invalid) { /* Missing or invalid MIME is not a supported codec. */ }
            if (!mime.startsWith("audio/") && codec == null) continue;
            audio++;
            if (codec != null) compatible++;
            direct |= !format.get("url").safeText().isBlank();
            cipher |= !format.get("signatureCipher").safeText().isBlank();
            String rawUrl = format.get("url").text();
            if (rawUrl == null) {
                rawUrl = com.sedmelluq.discord.lavaplayer.tools.DataFormatTools.decodeUrlEncodedItems(
                        format.get("signatureCipher").safeText(), true).get("url");
            }
            if (rawUrl != null) {
                try { n |= CipherDiagnosticTrace.parameter(URI.create(rawUrl), "n") != null; }
                catch (IllegalArgumentException invalid) { /* The library extraction below records unusable formats. */ }
            }
            // Use the installed library's extraction rules, including contentLength and codec handling.
            List<StreamFormat> extracted = new ArrayList<>();
            try {
                probe.extract(format, extracted, json.get("videoDetails").get("isLive").asBoolean(false));
                for (StreamFormat candidate : extracted) {
                    n |= candidate.getNParameter() != null && !candidate.getNParameter().isBlank();
                    if (candidate.getInfo() != null && CipherDiagnosticTrace.validUrl(candidate.getUrl())) formats.add(candidate);
                }
            } catch (RuntimeException invalid) {
                malformed++;
            }
        }
        boolean sabr = !data.get("serverAbrStreamingUrl").safeText().isBlank();
        report.put("audioFormatsCount", data.isNull() ? "UNKNOWN" : audio);
        report.put("usableFormatsCount", data.isNull() ? "UNKNOWN" : formats.size());
        report.put("supportedCodec", data.isNull() ? "UNKNOWN" : compatible > 0);
        report.put("malformedFormatsCount", data.isNull() ? "UNKNOWN" : malformed);
        report.put("directAudioUrl", data.isNull() ? "UNKNOWN" : direct);
        report.put("signatureCipherPresent", data.isNull() ? "UNKNOWN" : cipher);
        report.put("nPresent", data.isNull() ? "UNKNOWN" : n);
        report.put("sabrPresent", data.isNull() ? "UNKNOWN" : sabr);
        report.put("sabrOnly", data.isNull() ? "UNKNOWN" : sabr && !direct && !cipher);
        if ("INCONCLUSIVE".equals(report.get("conclusion"))) {
            if (!"OK".equals(status)) fail("PLAYER_API", "PLAYER_RESPONSE_INCOMPLETE_OR_UNPLAYABLE", "INCONCLUSIVE");
            else if (data.isNull()) fail("PLAYER_API", "PLAYER_RESPONSE_INCOMPLETE", "INCONCLUSIVE");
            else if (formats.isEmpty()) fail("FORMAT_SELECTION", "NO_SUPPORTED_AUDIO_STREAM",
                    sabr && !direct && !cipher ? "STREAM_FORMAT_UNSUPPORTED" : "CIPHER_NOT_INVOKED");
        }
        return new TrackFormats(formats, null);
    }

    private void live(boolean stream, boolean cipherAllowed, boolean remote) {
        report.put("mode", "LIVE");
        report.put("cipherAllowed", cipherAllowed);
        report.put("cipherImplementation", remote ? "REMOTE" : "LOCAL");
        report.put("authMode", "NONE");
        String remoteUrl = remote ? System.getenv("NORULE_DIAGNOSTIC_CIPHER_URL") : null;
        if (remote && (remoteUrl == null || !validCipherEndpoint(remoteUrl))) {
            fail("INPUT", "INVALID_DIAGNOSTIC_CIPHER_ENDPOINT", "INCONCLUSIVE");
            return;
        }
        var client = new Web() {
            private int playerRequests;
            @Override
            protected JsonBrowser loadJsonResponse(HttpInterface http, HttpPost request, String context) throws IOException {
                if (!request.getURI().getPath().endsWith("/player") || ++playerRequests > 1) {
                    throw new IOException("Diagnostic player request budget exhausted");
                }
                try (var result = http.execute(request)) {
                    int status = result.getStatusLine().getStatusCode();
                    report.put("playerHttpStatus", status);
                    if (status != 200) throw new HttpResponseException(status, "Player API rejected diagnostic");
                    byte[] bytes = result.getEntity().getContent().readNBytes(MAX_JSON_BYTES + 1);
                    if (bytes.length > MAX_JSON_BYTES) throw new IOException("Player response exceeds diagnostic limit");
                    JsonBrowser json = JsonBrowser.parse(new String(bytes, StandardCharsets.UTF_8));
                    inspect(json);
                    // inspect before upstream playability checks or embedded fallback can run.
                    if (!"OK".equals(report.get("playability"))) throw new IOException("Player response is not playable");
                    return json;
                }
            }
        };
        var source = new YoutubeAudioSourceManager(client);
        var localCipher = new LocalSignatureCipherManager() {
            @Override public String getScriptTimestamp(HttpInterface http, String script, String url) {
                trace.scriptFetched();
                return super.getScriptTimestamp(http, script, url);
            }
        };
        var guard = new PolicyCipherManager(new CipherPolicy(cipherAllowed),
                () -> remote ? new dev.lavalink.youtube.cipher.RemoteCipherManager(remoteUrl) : localCipher, trace);
        source.setCipherManager(guard);
        source.getContextFilter().setCipherConfig(System.getenv("NORULE_DIAGNOSTIC_CIPHER_PASSWORD"),
                "norule-youtube-diagnostic", YoutubeSource.VERSION);
        var baseFilter = source.getContextFilter();
        source.getHttpInterfaceManager().setHttpContextFilter(new com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter() {
            private int requests;
            @Override public void onContextOpen(org.apache.http.client.protocol.HttpClientContext context) {
                baseFilter.onContextOpen(context);
            }
            @Override public void onContextClose(org.apache.http.client.protocol.HttpClientContext context) {
                baseFilter.onContextClose(context);
            }
            @Override public void onRequest(org.apache.http.client.protocol.HttpClientContext context,
                    org.apache.http.client.methods.HttpUriRequest request, boolean repeated) {
                if (repeated || ++requests > 8) throw new IllegalStateException("Diagnostic HTTP budget exhausted");
                report.put("sourceHttpRequests", requests);
                report.put("lastHttpStage", remote && sameOrigin(request.getURI(), URI.create(remoteUrl)) ? "CIPHER_SERVICE"
                        : request.getURI().getPath().endsWith("/player") ? "PLAYER_API"
                        : request.getURI().getPath().endsWith(".js") ? "PLAYER_SCRIPT" : "CLIENT_CONFIG");
                baseFilter.onRequest(context, request, false);
            }
            @Override public boolean onRequestResponse(org.apache.http.client.protocol.HttpClientContext context,
                    org.apache.http.client.methods.HttpUriRequest request, org.apache.http.HttpResponse response) {
                report.put("lastHttpStatus", response.getStatusLine().getStatusCode());
                if (request.getURI().getPath().endsWith(".js")) {
                    trace.playerScript = response.getStatusLine().getStatusCode() == 200 ? "HTTP_SUCCESS" : "FAILED";
                }
                return false;
            }
            @Override public boolean onRequestException(org.apache.http.client.protocol.HttpClientContext context,
                    org.apache.http.client.methods.HttpUriRequest request, Throwable failure) { return false; }
        });
        source.getHttpInterfaceManager().configureBuilder(builder -> builder
                .disableAutomaticRetries().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000)
                        .setConnectionRequestTimeout(5000).setSocketTimeout(5000).build()));
        String stage = "PLAYER_SCRIPT_OR_METADATA";
        try (HttpInterface http = source.getInterface()) {
            // Call one client directly, never YoutubeAudioTrack.process() or its per-client loop.
            TrackFormats loaded = client.loadFormats(source, http, report.get("videoId").toString());
            if (response == null) throw new IOException("No Player response captured");
            TrackFormats supported = inspect(response);
            if (supported.getFormats().isEmpty()) return;
            stage = "FORMAT_SELECTION";
            StreamFormat selected = supported.getBestFormat();
            stage = "CIPHER";
            URI resolved = guard.resolveFormatUrl(http, loaded.getPlayerScriptUrl(), selected);
            if (!CipherDiagnosticTrace.validUrl(resolved)
                    || "INVALID_RESULT".equals(trace.signature) || "INVALID_RESULT".equals(trace.n)) {
                fail(stage, "INVALID_CIPHER_RESULT", "CIPHER_BROKEN");
                return;
            }
            report.put("conclusion", trace.invoked ? "INCONCLUSIVE" : "CIPHER_NOT_INVOKED");
            if ("SUCCESS".equals(trace.signature) || "SUCCESS".equals(trace.n)) report.put("conclusion", "CIPHER_WORKING");
            report.put("failureStage", "NONE");
            report.put("failureCategory", "NONE");
            if (stream) {
                stage = "STREAM_ACCESS";
                URI transformed = client.transformPlaybackUri(selected.getUrl(), resolved);
                DiagnosticStreamProbe.run(transformed, report);
            }
        } catch (dev.lavalink.youtube.CannotBeLoaded failure) {
            if ("UNKNOWN".equals(report.get("failureCategory"))) classifyFailure(stage, failure);
        } catch (Exception failure) {
            if ("UNKNOWN".equals(report.get("failureCategory")) || "NONE".equals(report.get("failureCategory"))) {
                classifyFailure(stage, failure);
            }
        } finally {
            if (remote && trace.invoked) trace.playerScript = "UNKNOWN";
            source.shutdown();
        }
    }

    void classifyFailure(String stage, Throwable failure) {
        var classified = new YoutubeFailureClassifier().classify(failure);
        String category = classified.category().name();
        Integer status = classified.httpStatus();
        if (failure instanceof HttpResponseException http) status = http.getStatusCode();
        if (Integer.valueOf(429).equals(status)) category = "HTTP_RATE_LIMITED";
        if (Integer.valueOf(404).equals(status)) category = "HTTP_NOT_FOUND";
        if (("PLAYER_SCRIPT_OR_METADATA".equals(stage) || "CIPHER".equals(stage)) && report.containsKey("lastHttpStage")) {
            stage = report.get("lastHttpStage").toString();
        }
        if (failure instanceof dev.lavalink.youtube.cipher.ScriptExtractionException) {
            trace.functionParse = "FAILED";
            stage = "PLAYER_SCRIPT_PARSE";
        }
        boolean restricted = !"CIPHER_SERVICE".equals(stage)
                && List.of("BOT_DETECTED", "LOGIN_REQUIRED", "HTTP_FORBIDDEN", "HTTP_RATE_LIMITED").contains(category);
        fail(stage, category, restricted ? "YOUTUBE_ACCESS_RESTRICTED"
                : List.of("CIPHER_FAILURE", "SIGNATURE_FAILURE").contains(category)
                    ? "CIPHER_BROKEN" : "NETWORK_OR_SERVICE_FAILURE");
    }

    void fail(String stage, String category, String conclusion) {
        report.put("failureStage", stage);
        report.put("failureCategory", category);
        report.put("conclusion", conclusion);
    }

    String output() {
        report.put("cipherInvoked", trace.invoked);
        report.put("cipherResolveInvoked", trace.resolveInvoked);
        if (trace.invoked && "CIPHER_NOT_INVOKED".equals(report.get("conclusion"))) {
            report.put("conclusion", "INCONCLUSIVE");
        }
        report.put("playerScript", trace.playerScript);
        report.put("scriptMetadata", trace.scriptMetadata);
        report.put("functionParse", trace.functionParse);
        report.put("signatureDecipher", trace.signature);
        report.put("nTransform", trace.n);
        StringBuilder out = new StringBuilder("[YouTubeCipherDiagnostic]\n");
        report.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        // Upstream DEBUG/TRACE and script extraction logs can contain signed URLs or script bodies.
        // This dedicated process emits only our allowlisted report, including when the library fails.
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        logging.reset();
        logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        try {
            runCli(args);
        } catch (IOException | RuntimeException failure) {
            // Parser/file exceptions can contain raw fixture fragments or paths. Never print their messages.
            System.out.println("[YouTubeCipherDiagnostic]\nfailureStage=INPUT\nfailureCategory=INVALID_INPUT\nconclusion=INCONCLUSIVE");
        }
    }

    private static void runCli(String[] args) throws IOException {
        if (args.length < 2 || !("--fixture".equals(args[0]) || "--live".equals(args[0]))) {
            System.out.println("Usage: --fixture <json-path> | --live <videoId> [--stream] [--cipher-disabled] [--cipher-remote]");
            return;
        }
        for (int i = 2; i < args.length; i++) {
            if (!List.of("--stream", "--cipher-disabled", "--cipher-remote").contains(args[i])) throw new IllegalArgumentException("Unknown option");
        }
        var diagnostic = new YouTubeCipherDiagnostic("--live".equals(args[0]) ? args[1] : "9VYPMwY74IY");
        if ("--fixture".equals(args[0])) {
            Path path = Path.of(args[1]);
            if (Files.size(path) > MAX_JSON_BYTES) throw new IOException("Fixture exceeds diagnostic limit");
            diagnostic.report.put("mode", "OFFLINE_FIXTURE");
            diagnostic.report.put("cipherAllowed", "UNKNOWN");
            JsonBrowser fixture = JsonBrowser.parse(Files.readString(path, StandardCharsets.UTF_8));
            String fixtureId = fixture.get("videoDetails").get("videoId").safeText();
            diagnostic.report.put("videoId", fixtureId.matches("[A-Za-z0-9_-]{11}") ? fixtureId : "UNKNOWN");
            diagnostic.inspect(fixture);
        } else {
            diagnostic.live(List.of(args).contains("--stream"), !List.of(args).contains("--cipher-disabled"),
                    List.of(args).contains("--cipher-remote"));
        }
        System.out.print(diagnostic.output());
    }

    private static final class FormatProbe extends Web {
        void extract(JsonBrowser json, List<StreamFormat> formats, boolean live) { extractFormat(json, formats, live); }
    }

    static boolean validCipherEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean sameOrigin(URI first, URI second) {
        return java.util.Objects.equals(first.getHost(), second.getHost())
                && java.util.Objects.equals(first.getScheme(), second.getScheme()) && first.getPort() == second.getPort();
    }
}
