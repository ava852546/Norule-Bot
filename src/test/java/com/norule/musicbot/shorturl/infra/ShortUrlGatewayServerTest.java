package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.ShortUrlStatistics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlGatewayServerTest {
    @Test
    void rendersPasswordPageWithoutTreatingCssPercentAsFormatSpecifier() {
        String html = ShortUrlGatewayServer.buildImagePasswordPage("6CxvDMr");

        assertTrue(html.contains("NoRule URL"));
        assertTrue(html.contains("/api/short/image/access/6CxvDMr"));
        assertFalse(html.contains("__IMAGE_CODE__"));
    }

    @Test
    void rendersImageInsideBrandedViewerWithViewCount() {
        ImageShare imageShare = new ImageShare(
                "6CxvDMr", "6CxvDMr.png", "image/png", 2048L,
                1_700_000_000_000L, 1_800_000_000_000L, "", "hash", 42L
        );

        String html = ShortUrlGatewayServer.buildImageViewPage(imageShare);

        assertTrue(html.contains("NoRule URL"));
        assertTrue(html.contains("/api/short/image/content/6CxvDMr"));
        assertTrue(html.contains(">42<"));
        assertFalse(html.contains("__IMAGE_VIEWS__"));
    }

    @Test
    void rendersVideoPlayerInsideBrandedViewer() {
        ImageShare videoShare = new ImageShare(
                "video01", "video01.mp4", "video/mp4", 1024L,
                1_700_000_000_000L, 1_800_000_000_000L, "", "hash", 3L
        );

        String html = ShortUrlGatewayServer.buildImageViewPage(videoShare);

        assertTrue(html.contains("<video controls"));
        assertTrue(html.contains("type=\"video/mp4\""));
        assertTrue(html.contains("/api/short/image/content/video01"));
        assertFalse(html.contains("__MEDIA_ELEMENT__"));
    }

    @Test
    void rendersAnExpiredSharePage() {
        String html = ShortUrlGatewayServer.buildImageExpiredPage();

        assertTrue(html.contains("410"));
        assertTrue(html.contains("此分享已到期"));
    }

    @Test
    void parsesSingleByteRangesForVideoStreaming() {
        assertEquals(new ShortUrlGatewayServer.ByteRange(0L, 99L),
                ShortUrlGatewayServer.parseByteRange("bytes=0-99", 1000L));
        assertEquals(new ShortUrlGatewayServer.ByteRange(900L, 999L),
                ShortUrlGatewayServer.parseByteRange("bytes=-100", 1000L));
        assertEquals(new ShortUrlGatewayServer.ByteRange(500L, 999L),
                ShortUrlGatewayServer.parseByteRange("bytes=500-", 1000L));
        assertEquals(new ShortUrlGatewayServer.ByteRange(0L, 9L),
                ShortUrlGatewayServer.parseByteRange("BYTES=0-9", 1000L));
        assertNull(ShortUrlGatewayServer.parseByteRange("bytes=1000-", 1000L));
        assertNull(ShortUrlGatewayServer.parseByteRange("bytes=0-1,4-5", 1000L));
    }

    @Test
    void recognizesStatsParameterButNotAnEmptyTrailingQuery() {
        assertTrue(ShortUrlGatewayServer.isStatisticsQuery("stats"));
        assertTrue(ShortUrlGatewayServer.isStatisticsQuery("stats&__nr_auth=ticket"));
        assertFalse(ShortUrlGatewayServer.isStatisticsQuery(""));
        assertFalse(ShortUrlGatewayServer.isStatisticsQuery(null));
        assertFalse(ShortUrlGatewayServer.isStatisticsQuery("view=stats"));
    }

    @Test
    void rendersAggregateStatisticsWithoutVisitorDetails() {
        ShortUrlStatistics statistics = new ShortUrlStatistics(
                ShortUrlStatistics.ResourceType.SHORT_URL,
                "safe-code",
                18L,
                1_700_000_000_000L,
                1_710_000_000_000L,
                1_800_000_000_000L
        );

        String html = ShortUrlGatewayServer.buildStatisticsPage(statistics);

        assertTrue(html.contains("存取統計"));
        assertTrue(html.contains("safe-code"));
        assertTrue(html.contains(">18<"));
        assertFalse(html.contains("IP 位址"));
        assertFalse(html.contains("__RESOURCE_"));
    }
}
