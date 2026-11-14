package com.norule.musicbot.gateway.youtube;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One bounded media request, followed by decoding a local prefix; no remote decoder or next-track loop. */
final class DiagnosticStreamProbe {
    static final int MAX_BYTES = 256 * 1024;

    static void run(URI uri, Map<String, Object> report) throws Exception {
        if (!CipherDiagnosticTrace.validUrl(uri) || !(uri.getHost().endsWith(".googlevideo.com"))) {
            throw new IOException("Unexpected media origin");
        }
        Path prefix = Files.createTempFile("norule-youtube-probe-", ".media");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Range", "bytes=0-" + (MAX_BYTES - 1)).GET().build();
            // ofByteArray subscriber cancels after the limit; timeout also covers the body.
            HttpResponse<byte[]> response = client.send(request,
                    info -> new LimitedBodySubscriber(MAX_BYTES));
            report.put("streamHttpStatus", response.statusCode());
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                report.put("streamAccess", "FAILED");
                throw new org.apache.http.client.HttpResponseException(response.statusCode(), "Media request rejected");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            boolean mediaType = java.util.List.of("audio/webm", "audio/mp4", "video/webm", "video/mp4",
                    "application/octet-stream").contains(contentType);
            report.put("streamContentType", mediaType ? contentType : "UNEXPECTED");
            report.put("downloadedBytes", response.body().length);
            if (!mediaType || response.body().length == 0) {
                report.put("streamAccess", "INVALID_CONTENT");
                report.put("failureStage", "STREAM_ACCESS");
                report.put("failureCategory", "INVALID_AUDIO_CONTENT");
                report.put("conclusion", "NETWORK_OR_SERVICE_FAILURE");
                return;
            }
            Files.write(prefix, response.body());
            report.put("streamAccess", "SUCCESS");
            decode(prefix, report);
        } finally {
            Files.deleteIfExists(prefix);
        }
    }

    static void decode(Path prefix, Map<String, Object> report) throws Exception {
        var manager = new DefaultAudioPlayerManager();
        var player = manager.createPlayer();
        try {
            var local = new LocalAudioSourceManager();
            manager.registerSourceManager(local);
            var item = local.loadItem(manager, new AudioReference(prefix.toString(), "diagnostic"));
            if (item instanceof AudioTrack track) {
                player.playTrack(track);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    var frame = player.provide(100, TimeUnit.MILLISECONDS);
                    if (frame != null && !frame.isTerminator() && frame.getDataLength() > 0) {
                        report.put("decode", "AUDIO_FRAME_CONFIRMED");
                        report.put("playback", "LOCAL_FRAME_ONLY");
                        return;
                    }
                }
            }
            report.put("decode", "INCONCLUSIVE_TRUNCATED_PREFIX");
        } catch (RuntimeException failure) {
            report.put("decode", "INCONCLUSIVE_TRUNCATED_PREFIX");
        } finally {
            player.destroy();
            manager.shutdown();
        }
    }

    /** Cancels upstream once the prefix is collected, even if Range was ignored. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final int limit;
        private java.util.concurrent.Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) {
            this.limit = limit;
            result.orTimeout(8, TimeUnit.SECONDS).whenComplete((value, failure) -> {
                if (failure != null && subscription != null) subscription.cancel();
            });
        }
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                int count = Math.min(buffer.remaining(), limit - bytes.size());
                byte[] chunk = new byte[count];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                if (bytes.size() >= limit) {
                    subscription.cancel();
                    result.complete(bytes.toByteArray());
                    return;
                }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
