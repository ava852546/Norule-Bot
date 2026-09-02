package com.norule.musicbot.domain.music.bilibili;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliMetadataCacheTest {
    @Test
    void storesMetadataAndExpiresByTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        BilibiliMetadataCache cache = new BilibiliMetadataCache(true, Duration.ofHours(12), 1000, clock);
        cache.put(metadata("BV1Na4Q64Eos", "first"));

        assertEquals("first", cache.get("BV1Na4Q64Eos").orElseThrow().title());
        clock.advance(Duration.ofHours(12));
        assertTrue(cache.get("BV1Na4Q64Eos").isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void evictsLeastRecentlyUsedEntryAtMaximumSize() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        BilibiliMetadataCache cache = new BilibiliMetadataCache(true, Duration.ofHours(12), 2, clock);
        cache.put(metadata("BV1Na4Q64Eos", "first"));
        cache.put(metadata("BV1xx411c7mD", "second"));
        assertTrue(cache.get("BV1Na4Q64Eos").isPresent());

        cache.put(metadata("BV1Q541167Qg", "third"));

        assertFalse(cache.get("BV1xx411c7mD").isPresent());
        assertTrue(cache.get("BV1Na4Q64Eos").isPresent());
        assertTrue(cache.get("BV1Q541167Qg").isPresent());
        assertEquals(2, cache.size());
    }

    @Test
    void normalizesUrlsWithAndWithoutTrackingQueryToSameBvid() {
        String plain = BilibiliVideoIdentifier.from(
                "https://www.bilibili.com/video/BV1Na4Q64Eos/").orElseThrow().bvid();
        String tracked = BilibiliVideoIdentifier.from(
                "https://www.bilibili.com/video/BV1Na4Q64Eos/?spm_id_from=333.1").orElseThrow().bvid();

        assertEquals("BV1Na4Q64Eos", plain);
        assertEquals(plain, tracked);
    }

    private BilibiliMetadata metadata(String bvid, String title) {
        BilibiliMetadata.Page page = new BilibiliMetadata.Page(
                1, 123L, title, "author", 60_000L, "thumbnail", "https://example.test/", bvid,
                false, "", "VIDEO", bvid);
        return new BilibiliMetadata(
                bvid, 123L, title, "author", 60_000L, "thumbnail", "https://example.test/", title,
                false, false, 1, List.of(page));
    }
}
