package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureClassifier;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.bilibili.BilibiliAudioTrack;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Uses the existing MP4/AAC decoder; signed CDN URLs are resolved per playback, never cached in metadata. */
final class BilibiliDashAudioTrack extends DelegatedAudioTrack {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(BilibiliDashAudioTrack.class);
    private final String id;
    private final Long cid;
    private final BilibiliAudioSourceAdapter source;

    BilibiliDashAudioTrack(AudioTrackInfo info, String id, Long cid, BilibiliAudioSourceAdapter source) {
        super(info);
        this.id = id;
        this.cid = cid;
        this.source = source;
    }

    public String getId() { return id; }
    public Long getCid() { return cid; }

    @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface http = source.httpInterface()) {
            List<URI> urls = playbackUrls(http);
            try (var stream = new DashStream(http, urls)) {
                processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
            }
        } catch (BilibiliRequestException failure) {
            if (failure.category().isAudioSelectionFailure()) {
                // Expected deterministic selection failures are summarized by MusicPlayerService.
                // COMMON keeps LavaPlayer from also printing a FAULT stack trace for this event.
                throw new FriendlyException(failure.getMessage(), FriendlyException.Severity.COMMON, failure);
            }
            throw failure;
        } catch (Exception failure) {
            // The nested stack identifies MP4 header parsing, AAC initialisation, decoding or PCM pipeline.
            throw new BilibiliRequestException(BilibiliFailureCategory.BILIBILI_PLAYBACK_FAILED,
                    processingStage(failure), 0, "Bilibili MP4/AAC processing failed",
                    new BilibiliFailureClassifier().classify(failure, BilibiliFailureStage.PLAYBACK).retryable(),
                    id, failure);
        }
    }

    static BilibiliFailureStage processingStage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            for (StackTraceElement frame : cause.getStackTrace()) {
                String name = frame.getClassName();
                if (name.contains("AudioPipeline") || name.contains("filter.")) return BilibiliFailureStage.PCM_OUTPUT;
                if (name.contains("AacDecoder") || name.contains("MpegAacTrackConsumer")) return BilibiliFailureStage.AAC_DECODE;
                if (name.contains("MpegFile") || name.contains("container.mpeg.reader")) return BilibiliFailureStage.MP4_PARSE;
            }
        }
        return BilibiliFailureStage.PLAYBACK;
    }

    private List<URI> playbackUrls(HttpInterface http) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                return requestPlaybackUrls(http);
            } catch (BilibiliRequestException failure) {
                if (failure.httpStatus() < 500 || attempt >= 2) throw failure;
                backoff(attempt);
            } catch (IOException failure) {
                throw new BilibiliRequestException(BilibiliFailureCategory.BILIBILI_PLAYBACK_FAILED,
                        BilibiliFailureStage.PLAY_URL, 0, "Bilibili PlayURL request failed",
                        new BilibiliFailureClassifier().classify(failure, BilibiliFailureStage.PLAY_URL).retryable(),
                        id, failure);
            }
        }
    }

    private static void backoff(int attempt) throws IOException {
        try {
            Thread.sleep(250L << attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Bilibili HTTP backoff interrupted", interrupted);
        }
    }

    private List<URI> requestPlaybackUrls(HttpInterface http) throws IOException {
        try (var response = http.execute(new HttpGet("https://api.bilibili.com/x/player/playurl?bvid="
                + id + "&cid=" + cid + "&fnval=16&fnver=0&fourk=0"))) {
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) throw failure(BilibiliFailureStage.PLAY_URL, status, "PlayURL HTTP " + status);
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonBrowser root;
            try {
                root = JsonBrowser.parse(body);
            } catch (IOException malformed) {
                throw new BilibiliRequestException(BilibiliFailureCategory.AUDIO_RESPONSE_INVALID,
                        BilibiliFailureStage.PLAY_URL, 0,
                        "Bilibili PlayURL invalid JSON: playUrlHttpStatus=" + status + " playUrlApiCode=UNKNOWN",
                        false, id, null);
            }
            int code = root.get("code").asInt(Integer.MIN_VALUE);
            if (code == Integer.MIN_VALUE) {
                throw new BilibiliRequestException(BilibiliFailureCategory.AUDIO_RESPONSE_INVALID,
                        BilibiliFailureStage.PLAY_URL, 0,
                        "Bilibili PlayURL missing API code: playUrlHttpStatus=" + status,
                        false, id, null);
            }
            if (code != 0) throw failure(BilibiliFailureStage.PLAY_URL, 0, "PlayURL API code " + code);
            var selection = BilibiliDashAudioSelector.select(root.get("data"), id, cid, status, code);
            LOGGER.debug(
                    "[Bilibili] AUDIO_SELECTED videoId={} codec={} {}", id, selection.codec(), selection.diagnostics());
            return selection.urls();
        }
    }

    static List<URI> audioUrls(JsonBrowser data) throws IOException {
        return BilibiliDashAudioSelector.select(data, "", null, 0, null).urls();
    }

    private BilibiliRequestException failure(BilibiliFailureStage stage, int status, String message) {
        BilibiliFailureCategory category = switch (status) {
            case 403 -> BilibiliFailureCategory.BILIBILI_ACCESS_DENIED;
            case 412 -> BilibiliFailureCategory.BILIBILI_RISK_CONTROL;
            case 429 -> BilibiliFailureCategory.BILIBILI_RATE_LIMITED;
            default -> BilibiliFailureCategory.BILIBILI_PLAYBACK_FAILED;
        };
        return new BilibiliRequestException(category, stage, status, "Bilibili " + message,
                status >= 500, id, null);
    }

    @Override protected AudioTrack makeShallowClone() {
        return new BilibiliDashAudioTrack(trackInfo, id, cid, source);
    }

    @Override public BilibiliAudioSourceAdapter getSourceManager() { return source; }

    BilibiliAudioTrack vendorTrack() {
        return source.vendorVideoTrack(trackInfo, id, cid);
    }

    final class DashStream extends PersistentHttpStream {
        private List<URI> urls;
        private int candidate;
        private boolean refreshed;

        DashStream(HttpInterface http, List<URI> urls) {
            super(http, urls.get(0), null);
            this.urls = urls;
        }

        @Override protected URI getConnectUrl() { return urls.get(candidate); }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read < 0 && getContentLength() > position) {
                // A clean TCP close before Content-Length is also a dropped connection.
                releaseConnection();
                read = super.read(buffer, offset, length);
                if (read < 0) throw new java.io.EOFException("Bilibili CDN ended before Content-Length");
            }
            return read;
        }

        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xff;
        }

        @Override protected void connect(boolean skipStatusCheck) throws IOException {
            if (getCurrentResponse() != null) return;
            int serverRetries = 0;
            while (true) {
                int status;
                try {
                    super.connect(true);
                    status = getCurrentResponse().getStatusLine().getStatusCode();
                } catch (IOException network) {
                    releaseConnection();
                    if (++candidate < urls.size()) continue;
                    throw new BilibiliRequestException(BilibiliFailureCategory.BILIBILI_PLAYBACK_FAILED,
                            BilibiliFailureStage.CDN, 0, "Bilibili CDN connection failed", true, id, network);
                }
                if (status == 200 || status == 206) {
                    if (position > 0 && (status != 206 || !validRange())) {
                        releaseConnection();
                        throw failure(BilibiliFailureStage.CDN, status, "CDN ignored or returned an invalid byte Range");
                    }
                    return;
                }
                releaseConnection();
                if (position == 0) contentLength = com.sedmelluq.discord.lavaplayer.tools.Units.CONTENT_LENGTH_UNKNOWN;
                if (status == 412 || status == 429) throw failure(BilibiliFailureStage.CDN, status, "CDN HTTP " + status);
                if (status >= 500 && serverRetries++ < 2) {
                    backoff(serverRetries - 1);
                    continue;
                }
                if (++candidate < urls.size()) continue;
                // Refresh expired signed URLs once at the beginning of playback. During playback the
                // track recovery budget controls reload/seek, avoiding mixing different byte layouts.
                if (status == 403 && !refreshed && position == 0) {
                    refreshed = true;
                    urls = playbackUrls(httpInterface);
                    candidate = 0;
                    continue;
                }
                if (status == 403 && position > 0) {
                    throw new BilibiliRequestException(BilibiliFailureCategory.BILIBILI_ACCESS_DENIED,
                            BilibiliFailureStage.CDN, status, "Bilibili CDN signed URL expired during playback",
                            true, id, null);
                }
                throw failure(BilibiliFailureStage.CDN, status, "CDN HTTP " + status);
            }
        }

        private boolean validRange() {
            var header = getCurrentResponse().getFirstHeader("Content-Range");
            return header != null && header.getValue().startsWith("bytes " + position + "-");
        }
    }
}
