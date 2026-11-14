package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.*;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliDashAudioSelectorTest {
    @TestFactory Stream<DynamicTest> offlineFixtures() throws Exception {
        List<JsonBrowser> fixtures;
        try (var stream = getClass().getResourceAsStream("/bilibili/audio-selection.json")) {
            assertNotNull(stream);
            fixtures = JsonBrowser.parse(stream).values();
        }
        return fixtures.stream().map(fixture -> DynamicTest.dynamicTest(fixture.get("name").safeText(), () -> {
            if (fixture.get("category").isNull()) {
                var selection = BilibiliDashAudioSelector.select(fixture.get("data"), "BV1BM4m1S7w1", 1L, 200, 0);
                assertEquals(fixture.get("codec").safeText(), selection.codec());
                assertFalse(selection.urls().isEmpty());
                assertFalse(selection.diagnostics().contains("SECRET"));
                assertFalse(selection.diagnostics().contains("https://"));
            } else {
                var failure = assertThrows(BilibiliRequestException.class,
                        () -> BilibiliDashAudioSelector.select(fixture.get("data"), "BV1BM4m1S7w1", 1L, 200, 0));
                assertEquals(fixture.get("category").safeText(), failure.category().name());
                assertEquals(BilibiliFailureStage.AUDIO_SELECTION, failure.stage());
                var wrapped = new FriendlyException(failure.getMessage(), FriendlyException.Severity.COMMON, failure);
                var report = new BilibiliFailureClassifier().classify(wrapped, BilibiliFailureStage.PLAYBACK);
                assertEquals(failure.category(), report.category());
                assertFalse(report.retryable());
                assertFalse(report.breakerFailure());
                assertEquals("BILIBILI_" + failure.category(), report.errorKey());
                assertTrue(failure.getMessage().contains("playUrlHttpStatus=200 playUrlApiCode=0"));
                assertFalse(failure.getMessage().contains("SECRET"));
                assertFalse(failure.getMessage().contains("https://"));
            }
        }));
    }

    @Test void prefersQualityAndRetainsOnlyTheSelectedRepresentationsBackups() throws Exception {
        var selection = BilibiliDashAudioSelector.select(JsonBrowser.parse("""
                {"dash":{"audio":[
                  {"codecs":"mp4a.40.2","bandwidth":1,"baseUrl":"https://cdn.example/low"},
                  {"codecs":"mp4a.40.2","bandwidth":2,"baseUrl":"https://cdn.example/high",
                    "base_url":"https://cdn.example/high","backupUrl":["https://cdn.example/backup"]}]}}
                """), "BV1BM4m1S7w1", null, 0, null);
        assertEquals(List.of("https://cdn.example/high", "https://cdn.example/backup"),
                selection.urls().stream().map(Object::toString).toList());
        assertTrue(selection.diagnostics().contains("playUrlHttpStatus=UNKNOWN playUrlApiCode=UNKNOWN"));
        assertTrue(selection.diagnostics().contains("aacLcCount=2 aacLcWithValidUrl=2"));
    }

    @Test void diagnosticFieldsCannotInjectUrlsOrNewlines() throws Exception {
        var failure = assertThrows(BilibiliRequestException.class, () -> BilibiliDashAudioSelector.select(
                JsonBrowser.parse("""
                {"dash":{"audio":[{"codecs":"https://secret?token=SECRET","mimeType":"bad\\nSECRET"}]}}
                """), "BV1BM4m1S7w1", null, 200, 0));
        assertFalse(failure.getMessage().contains("SECRET"));
        assertFalse(failure.getMessage().contains("\n"));
    }
}
