package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.CipherDisabledException;
import com.norule.musicbot.domain.music.CipherPolicy;
import com.norule.musicbot.domain.music.YoutubeFailureClassifier;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.cipher.CipherManager;
import dev.lavalink.youtube.cipher.LocalSignatureCipherManager;
import dev.lavalink.youtube.cipher.ScriptExtractionException;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.apache.http.client.HttpResponseException;
import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class YouTubeCipherDiagnosticTest {
    @ParameterizedTest
    @CsvSource({
            "false,false", "true,false", "false,true", "true,true"
    })
    void directSignatureAndNFormatsUseInstalledExtractionRules(boolean signature, boolean n) throws Exception {
        String url = "https://fixture.googlevideo.com/audio" + (n ? "?n=challenge" : "");
        String field = signature
                ? "\"signatureCipher\":\"url=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8) + "&s=encrypted&sp=sig\""
                : "\"url\":\"" + url + "\"";
        var diagnostic = diagnostic();
        var formats = diagnostic.inspect(JsonBrowser.parse(player("{\"adaptiveFormats\":[{" + field
                + ",\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"itag\":251,\"contentLength\":\"1024\",\"bitrate\":1000}]}")));
        assertEquals(1, formats.getFormats().size());
        assertEquals(!signature, diagnostic.report.get("directAudioUrl"));
        assertEquals(signature, diagnostic.report.get("signatureCipherPresent"));
        assertEquals(n, diagnostic.report.get("nPresent"));
        assertFalse(diagnostic.trace.invoked);
        assertTrue(diagnostic.output().contains("signatureDecipher=NOT_INVOKED"));
        assertFalse(diagnostic.output().contains("challenge"));
    }

    @Test
    void sabrDoesNotBecomeCipherFailureOrInventAdaptiveCount() throws Exception {
        var diagnostic = diagnostic();
        diagnostic.inspect(JsonBrowser.parse(player("{\"serverAbrStreamingUrl\":\"https://secret.invalid/?token=secret\"}")));
        assertEquals("STREAM_FORMAT_UNSUPPORTED", diagnostic.report.get("conclusion"));
        assertEquals("FORMAT_SELECTION", diagnostic.report.get("failureStage"));
        assertEquals("UNKNOWN", diagnostic.report.get("adaptiveFormatsCount"));
        assertTrue(diagnostic.output().contains("cipherInvoked=false"));
        assertFalse(diagnostic.output().contains("secret"));
    }

    @Test
    void missingResponseIsUnknownAndEmptyFormatsAreNotCipherFailure() throws Exception {
        var missing = diagnostic();
        missing.inspect(JsonBrowser.parse("{}"));
        assertEquals("UNKNOWN", missing.report.get("audioFormatsCount"));
        assertEquals("INCONCLUSIVE", missing.report.get("conclusion"));
        var empty = diagnostic();
        empty.inspect(JsonBrowser.parse(player("{\"adaptiveFormats\":[]}")));
        assertEquals(0, empty.report.get("adaptiveFormatsCount"));
        assertEquals("CIPHER_NOT_INVOKED", empty.report.get("conclusion"));
    }

    @Test
    void unusableCodecAndMissingUrlAreRejectedByRealFormatRules() throws Exception {
        for (String format : List.of(
                "{\"mimeType\":\"audio/unknown\",\"url\":\"https://fixture.googlevideo.com/audio\",\"contentLength\":100}",
                "{\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"contentLength\":100}")) {
            var diagnostic = diagnostic();
            diagnostic.inspect(JsonBrowser.parse(player("{\"adaptiveFormats\":[" + format + "]}")));
            assertEquals(0, diagnostic.report.get("usableFormatsCount"));
            assertEquals("NO_SUPPORTED_AUDIO_STREAM", diagnostic.report.get("failureCategory"));
        }
    }

    @Test
    void loginAndBotResponsesStopBeforeStreamAndRedactAllReasonText() throws Exception {
        var diagnostic = diagnostic();
        diagnostic.inspect(JsonBrowser.parse("{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\","
                + "\"reason\":\"Sign in to confirm you're not a bot. Cookie=secret visitorData=secret\"}}"));
        assertEquals("YOUTUBE_ACCESS_RESTRICTED", diagnostic.report.get("conclusion"));
        assertEquals("BOT_DETECTED", diagnostic.report.get("failureCategory"));
        assertEquals("NOT_TESTED", diagnostic.report.get("streamAccess"));
        assertFalse(diagnostic.output().contains("secret"));
    }

    @ParameterizedTest
    @CsvSource({"400,HTTP_BAD_REQUEST", "403,HTTP_FORBIDDEN", "429,HTTP_RATE_LIMITED"})
    void httpFailuresAreNotCipherFailures(int status, String expected) {
        var diagnostic = diagnostic();
        diagnostic.classifyFailure("PLAYER_API", new HttpResponseException(status, "token=secret"));
        assertEquals(expected, diagnostic.report.get("failureCategory"));
        assertFalse(diagnostic.output().contains("secret"));
        var report = new YoutubeFailureClassifier().classify(new HttpResponseException(status, "rejected"));
        assertEquals(expected, report.category().name());
        assertEquals(status, report.httpStatus());
        if (status == 429) {
            assertTrue(report.accessRestricted());
            assertFalse(report.allowsPlaybackRecovery(com.norule.musicbot.config.domain.MusicConfig.Youtube.AuthMode.OAUTH));
        }
    }

    @ParameterizedTest
    @CsvSource({"TIMESTAMP_NOT_FOUND,CIPHER_FAILURE", "SIG_ACTIONS_NOT_FOUND,SIGNATURE_FAILURE",
            "DECIPHER_FUNCTION_NOT_FOUND,SIGNATURE_FAILURE", "N_FUNCTION_NOT_FOUND,CIPHER_FAILURE", "VARIABLES_NOT_FOUND,CIPHER_FAILURE"})
    void extractionClassificationUsesTypedEvidence(String type, String expected) {
        var exception = new ScriptExtractionException("redacted",
                ScriptExtractionException.ExtractionFailureType.valueOf(type));
        assertEquals(expected, new YoutubeFailureClassifier().classify(exception).category().name());
    }

    @Test
    void timeoutAndStageAreRetained() {
        var diagnostic = diagnostic();
        diagnostic.classifyFailure("STREAM_ACCESS", new SocketTimeoutException());
        assertEquals("NETWORK_TIMEOUT", diagnostic.report.get("failureCategory"));
        assertEquals("STREAM_ACCESS", diagnostic.report.get("failureStage"));
    }

    @Test
    void remoteCipherRejectionIsNotYoutubeAccessEvidence() {
        var diagnostic = diagnostic();
        diagnostic.report.put("lastHttpStage", "CIPHER_SERVICE");
        diagnostic.classifyFailure("CIPHER", new HttpResponseException(403, "secret"));
        assertEquals("CIPHER_SERVICE", diagnostic.report.get("failureStage"));
        assertEquals("NETWORK_OR_SERVICE_FAILURE", diagnostic.report.get("conclusion"));
        assertTrue(YouTubeCipherDiagnostic.validCipherEndpoint("http://127.0.0.1:8001"));
        assertFalse(YouTubeCipherDiagnostic.validCipherEndpoint("https://user:secret@fixture.invalid"));
        assertFalse(YouTubeCipherDiagnostic.validCipherEndpoint("https://fixture.invalid/?token=secret"));
    }

    @Test
    void enabledPlainUrlNeverConstructsCipherAndIsNotRequired() throws Exception {
        var trace = new CipherDiagnosticTrace();
        var guard = new PolicyCipherManager(new CipherPolicy(true), () -> { throw new AssertionError("unnecessary cipher"); }, trace);
        assertEquals(format(null, null).getUrl(), guard.resolveFormatUrl(null, null, format(null, null)));
        assertFalse(trace.invoked);
        assertEquals("NOT_REQUIRED", trace.signature);
        assertEquals("NOT_REQUIRED", trace.n);
        assertEquals("NOT_REQUESTED", trace.playerScript);
    }

    @Test
    void disabledDiagnosticCannotConstructDelegateOrChangePolicy() {
        var policy = new CipherPolicy(false);
        var trace = new CipherDiagnosticTrace();
        var guard = new PolicyCipherManager(policy, () -> { throw new AssertionError("disabled"); }, trace);
        assertThrows(CipherDisabledException.class, () -> guard.resolveFormatUrl(null, null, format("encrypted", "challenge")));
        assertFalse(trace.invoked);
        assertFalse(policy.isAllowed());
    }

    @Test
    void nAsFirstQueryParameterCannotBypassDisabledPolicy() {
        var format = new StreamFormat(ContentType.parse("audio/webm; codecs=opus"), 251, 1000, 1000, 2,
                "https://fixture.googlevideo.com/audio?n=challenge", null, null, "sig", true, false);
        var disabled = new PolicyCipherManager(new CipherPolicy(false), () -> { throw new AssertionError("disabled"); });
        assertThrows(CipherDisabledException.class, () -> disabled.resolveFormatUrl(null, null, format));
        var trace = new CipherDiagnosticTrace();
        var enabled = new PolicyCipherManager(new CipherPolicy(true),
                () -> delegate(URI.create("https://fixture.googlevideo.com/audio?n=transformed"), false), trace);
        assertDoesNotThrow(() -> enabled.resolveFormatUrl(null, null, format));
        assertEquals("SUCCESS", trace.n);
        assertEquals("NOT_REQUIRED", trace.signature);
    }

    @Test
    void realLocalCipherTransformsFixedScriptThroughLocalHttpOnly() throws Exception {
        String script = "var config={signatureTimestamp:12345};var Q=[\"split\",\"\",\"join\"];"
                + "var A={a:function(a,b){a.reverse()},b:function(a,b){a.splice(0,b)},c:function(a,b){a.push(b)}};"
                + "function(a){a=a.split(\"\");A.a(a,1);return a.join(\"\")};"
                + "function(a){var b=a[Q[0]](Q[1]);try{b.reverse()}catch(c){return \"enhanced_except_\"+a}return b[Q[2]](Q[1])};";
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/player.js", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = script.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        var httpManager = com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.createDefaultThreadLocalManager();
        try (var http = httpManager.getInterface()) {
            var trace = new CipherDiagnosticTrace();
            var guard = new PolicyCipherManager(new CipherPolicy(true), LocalSignatureCipherManager::new, trace);
            URI result = guard.resolveFormatUrl(http, "http://127.0.0.1:" + server.getAddress().getPort() + "/player.js",
                    format("encrypted", "challenge"));
            assertEquals("detpyrcne", CipherDiagnosticTrace.parameter(result, "sig"));
            assertEquals("egnellahc", CipherDiagnosticTrace.parameter(result, "n"));
            assertEquals("SUCCESS", trace.signature);
            assertEquals("SUCCESS", trace.n);
            assertEquals(1, calls.get());
        } finally {
            httpManager.close();
            server.stop(0);
        }
    }

    @Test
    void successfulCombinedCallMustHaveValidTransformedResults() throws Exception {
        for (String resolved : List.of("https://fixture.googlevideo.com/audio?sig=deciphered&n=transformed",
                "https://fixture.googlevideo.com/audio?n=challenge", "https://fixture.googlevideo.com/audio?n=enhanced_except_x")) {
            var trace = new CipherDiagnosticTrace();
            CipherManager delegate = delegate(URI.create(resolved), false);
            var guard = new PolicyCipherManager(new CipherPolicy(true), () -> delegate, trace);
            guard.resolveFormatUrl(null, null, format("encrypted", "challenge"));
            assertTrue(trace.invoked);
            if (resolved.contains("sig=deciphered")) {
                assertEquals("SUCCESS", trace.signature);
                assertEquals("SUCCESS", trace.n);
                assertEquals("SUCCESS_INFERRED", trace.functionParse);
            } else {
                assertEquals("INVALID_RESULT", trace.signature);
                assertEquals("INVALID_RESULT", trace.n);
            }
        }
    }

    @Test
    void combinedFailureDoesNotInventWhichTransformRan() {
        var trace = new CipherDiagnosticTrace();
        var guard = new PolicyCipherManager(new CipherPolicy(true), () -> delegate(null, true), trace);
        assertThrows(IOException.class, () -> guard.resolveFormatUrl(null, null, format("encrypted", "challenge")));
        assertTrue(trace.invoked);
        assertEquals("UNKNOWN", trace.signature);
        assertEquals("UNKNOWN", trace.n);
    }

    @Test
    void realTimestampParserWorksWithFixedOfflineScript() {
        assertEquals("12345", new LocalSignatureCipherManager().getScriptTimestamp(null,
                "var fixture={signatureTimestamp:12345};", "https://fixture.invalid/player.js"));
    }

    @Test
    void ignoredRangeStillCancelsAtByteLimit() throws Exception {
        var cancelled = new AtomicBoolean();
        var subscriber = new DiagnosticStreamProbe.LimitedBodySubscriber(16);
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1000])));
        assertEquals(16, subscriber.getBody().toCompletableFuture().get().length);
        assertTrue(cancelled.get());
    }

    private static YouTubeCipherDiagnostic diagnostic() { return new YouTubeCipherDiagnostic("9VYPMwY74IY"); }
    private static String player(String data) { return "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":" + data + "}"; }
    private static StreamFormat format(String signature, String n) {
        return new StreamFormat(ContentType.parse("audio/webm; codecs=opus"), 251, 1000, 1000, 2,
                "https://fixture.googlevideo.com/audio" + (n == null ? "" : "?n=challenge"), n, signature, "sig", true, false);
    }
    private static CipherManager delegate(URI result, boolean fail) {
        return new CipherManager() {
            public URI resolveFormatUrl(HttpInterface http, String script, StreamFormat format) throws IOException {
                if (fail) throw new IOException("fixture");
                return result;
            }
            public CachedPlayerScript getCachedPlayerScript(HttpInterface http) { return null; }
            public String getTimestamp(HttpInterface http, String script) { return "123"; }
        };
    }
}
