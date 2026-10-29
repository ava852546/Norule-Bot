package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliDashAudioTrackTest {
    @Test void selectsAacLcAndItsBackupsWithoutMixingRepresentations() throws Exception {
        var urls = BilibiliDashAudioTrack.audioUrls(JsonBrowser.parse("""
                {"dash":{"audio":[
                  {"codecs":"ec-3","bandwidth":99999,"baseUrl":"https://cdn/unsupported"},
                  {"codecs":"mp4a.40.2","bandwidth":100,"base_url":"https://cdn/main", "backupUrl":["https://cdn/backup"]},
                  {"codecs":"mp4a.40.2","bandwidth":10,"baseUrl":"https://cdn/other"}]}}
                """));
        assertEquals(List.of(URI.create("https://cdn/main"), URI.create("https://cdn/backup")), urls);
        assertThrows(IOException.class, () -> BilibiliDashAudioTrack.audioUrls(JsonBrowser.parse("{}")));
    }

    @Test void failsOverAndReconnectsUsingByteRanges() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger ranges = new AtomicInteger();
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        server.createContext("/bad", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        server.createContext("/good", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
            if (range != null) ranges.incrementAndGet();
            if (range != null) exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-9/10");
            exchange.sendResponseHeaders(range == null ? 200 : 206, bytes.length - start);
            exchange.getResponseBody().write(bytes, start, bytes.length - start);
            exchange.close();
        });
        server.start();
        var adapter = new BilibiliAudioSourceAdapter(MusicConfig.defaultValues().getBilibili());
        var track = new BilibiliDashAudioTrack(new AudioTrackInfo("title", "author", 1000,
                "BV1VqsQ6KEoy", false, "https://www.bilibili.com/video/BV1VqsQ6KEoy/"),
                "BV1VqsQ6KEoy", 1L, adapter);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var http = adapter.httpInterface();
             var stream = track.new DashStream(http, List.of(URI.create(base + "/bad"), URI.create(base + "/good")))) {
            assertArrayEquals("0123".getBytes(StandardCharsets.UTF_8), stream.readNBytes(4));
            assertEquals(10, stream.getContentLength());
            stream.releaseConnection();
            assertArrayEquals("456789".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
            assertEquals(1, ranges.get());
        } finally { adapter.shutdown(); server.stop(0); }
    }

    @Test void preservesLegacyEncodingAndClonesIndependentTracks() throws Exception {
        var adapter = new BilibiliAudioSourceAdapter(MusicConfig.defaultValues().getBilibili());
        try {
            var info = new AudioTrackInfo("title", "author", 1000, "BV1VqsQ6KEoy", false, "https://www.bilibili.com/");
            var track = new BilibiliDashAudioTrack(info, "BV1VqsQ6KEoy", 123L, adapter);
            var bytes = new ByteArrayOutputStream();
            adapter.encodeTrack(track, new DataOutputStream(bytes));
            var decoded = assertInstanceOf(BilibiliDashAudioTrack.class,
                    adapter.decodeTrack(info, new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
            assertEquals(123L, decoded.getCid());
            assertSame(adapter, decoded.getSourceManager());
            assertNotSame(track, track.makeClone());
        } finally { adapter.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {412, 429})
    void riskControlStopsBeforeTryingBackup(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "120");
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        server.start();
        var adapter = new BilibiliAudioSourceAdapter(MusicConfig.defaultValues().getBilibili());
        var track = new BilibiliDashAudioTrack(new AudioTrackInfo("t", "a", 1000, "BV1VqsQ6KEoy", false, "https://www.bilibili.com/"),
                "BV1VqsQ6KEoy", 1L, adapter);
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try (var http = adapter.httpInterface(); var stream = track.new DashStream(http, List.of(url, url))) {
            var failure = assertThrows(BilibiliRequestException.class, stream::read);
            assertEquals(status, failure.httpStatus());
            assertFalse(failure.retryable());
            assertEquals(1, requests.get());
            assertThrows(BilibiliRequestException.class, () -> http.execute(new org.apache.http.client.methods.HttpGet(url)));
            assertEquals(1, requests.get());
        } finally { adapter.shutdown(); server.stop(0); }
    }

    @Test void retryAfterSupportsSecondsAndHttpDates() {
        var response = new org.apache.http.message.BasicHttpResponse(org.apache.http.HttpVersion.HTTP_1_1, 429, "Too Many Requests");
        response.setHeader("Retry-After", "120");
        assertEquals(120000L, BilibiliAudioSourceAdapter.retryAfterMillis(response));
        response.setHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT");
        assertEquals(0L, BilibiliAudioSourceAdapter.retryAfterMillis(response));
    }

    @Test void serverErrorsUseBoundedBackoffBeforeBackup() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/bad", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/good", exchange -> {
            exchange.sendResponseHeaders(200, 1); exchange.getResponseBody().write(42); exchange.close();
        });
        server.start();
        var adapter = new BilibiliAudioSourceAdapter(MusicConfig.defaultValues().getBilibili());
        var track = new BilibiliDashAudioTrack(new AudioTrackInfo("t", "a", 1000, "BV1VqsQ6KEoy", false, "https://www.bilibili.com/"),
                "BV1VqsQ6KEoy", 1L, adapter);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var http = adapter.httpInterface(); var stream = track.new DashStream(http,
                List.of(URI.create(base + "/bad"), URI.create(base + "/good")))) {
            assertEquals(42, stream.read());
            assertEquals(3, requests.get());
        } finally { adapter.shutdown(); server.stop(0); }
    }
}
