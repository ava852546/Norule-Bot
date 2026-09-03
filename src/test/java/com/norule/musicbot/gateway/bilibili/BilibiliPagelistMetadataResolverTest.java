package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliMetadata;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliPagelistMetadataResolverTest {
    @Test
    void parsesSuccessfulSinglePageResponse() throws Exception {
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        BilibiliPagelistMetadataResolver resolver = resolver(requestedUri, 200, singlePageJson());

        BilibiliMetadata metadata = resolver.resolve("BV1Na4Q64Eos", null);

        assertEquals("https://api.bilibili.com/x/player/pagelist?bvid=BV1Na4Q64Eos",
                requestedUri.get().toString());
        assertEquals(41_414_165_124L, metadata.cid());
        assertEquals("Part 1", metadata.title());
        assertEquals(1_474_000L, metadata.durationMillis());
        assertEquals(1, metadata.selectedPage());
        assertEquals("http://example.test/frame.jpg", metadata.thumbnail());
        assertTrue(metadata.degraded());
        assertEquals(1, metadata.pages().size());
    }

    @Test
    void selectsRequestedPageByApiPageNumber() throws Exception {
        BilibiliPagelistMetadataResolver resolver = resolver(new AtomicReference<>(), 200, multiPageJson());

        BilibiliMetadata metadata = resolver.resolve("BV1Na4Q64Eos", 2);

        assertEquals(222L, metadata.cid());
        assertEquals(2, metadata.selectedPage());
        assertEquals(3, metadata.pages().size());
        assertEquals(222L, metadata.pages().get(1).cid());
        assertEquals("https://www.bilibili.com/video/BV1Na4Q64Eos/?p=2",
                metadata.pages().get(1).webpageUrl());
    }

    @Test
    void rejectsRequestedPageMissingFromPagelist() {
        BilibiliPagelistMetadataResolver resolver = resolver(new AtomicReference<>(), 200, multiPageJson());

        BilibiliRequestException failure = assertThrows(
                BilibiliRequestException.class,
                () -> resolver.resolve("BV1Na4Q64Eos", 4)
        );

        assertEquals(BilibiliFailureCategory.BILIBILI_METADATA_FAILED, failure.category());
    }

    @Test
    void rejectsHttp200WhenApiCodeIsNotZero() {
        BilibiliPagelistMetadataResolver resolver = resolver(
                new AtomicReference<>(),
                200,
                """
                        {"code":-404,"message":"not found","data":null}
                        """
        );

        BilibiliRequestException failure = assertThrows(
                BilibiliRequestException.class,
                () -> resolver.resolve("BV1Na4Q64Eos", null)
        );

        assertEquals(BilibiliFailureCategory.BILIBILI_METADATA_FAILED, failure.category());
        assertEquals(200, failure.httpStatus());
    }

    @Test
    void classifiesPagelistHttpFailures() {
        assertHttpFailure(403, BilibiliFailureCategory.BILIBILI_ACCESS_DENIED);
        assertHttpFailure(412, BilibiliFailureCategory.BILIBILI_RISK_CONTROL);
        assertHttpFailure(429, BilibiliFailureCategory.BILIBILI_RATE_LIMITED);
    }

    private void assertHttpFailure(int status, BilibiliFailureCategory expectedCategory) {
        BilibiliPagelistMetadataResolver resolver = resolver(new AtomicReference<>(), status, "");
        BilibiliRequestException failure = assertThrows(
                BilibiliRequestException.class,
                () -> resolver.resolve("BV1Na4Q64Eos", null)
        );
        assertEquals(expectedCategory, failure.category());
        assertEquals(status, failure.httpStatus());
    }

    private BilibiliPagelistMetadataResolver resolver(AtomicReference<URI> requestedUri,
                                                       int status,
                                                       String body) {
        return new BilibiliPagelistMetadataResolver(uri -> {
            requestedUri.set(uri);
            return new BilibiliPagelistMetadataResolver.PagelistResponse(status, body);
        });
    }

    private String singlePageJson() {
        return """
                {
                  "code": 0,
                  "message": "OK",
                  "data": [
                    {
                      "cid": 41414165124,
                      "page": 1,
                      "from": "vupload",
                      "part": "Part 1",
                      "duration": 1474,
                      "first_frame": "http://example.test/frame.jpg"
                    }
                  ]
                }
                """;
    }

    static String multiPageJson() {
        return """
                {
                  "code": 0,
                  "message": "OK",
                  "data": [
                    {"cid":111,"page":1,"part":"Part 1","duration":10,"first_frame":""},
                    {"cid":222,"page":2,"part":"Part 2","duration":20,"first_frame":""},
                    {"cid":333,"page":3,"part":"Part 3","duration":30,"first_frame":""}
                  ]
                }
                """;
    }
}
