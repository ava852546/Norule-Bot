package com.norule.musicbot.gateway.bilibili;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BilibiliWbiMetadataResolverTest {
    @Test void loadsFullMetadataAndSignsViewRequestsWithCachedNavKeys() throws Exception {
        List<String> requests = new ArrayList<>();
        var resolver = new BilibiliWbiMetadataResolver(uri -> {
            requests.add(uri.toString());
            String body = uri.getPath().endsWith("nav") ? """
                    {"code":-101,"data":{"wbi_img":{
                    "img_url":"https://i/7cd084941338484aae1ad9425b84077c.png",
                    "sub_url":"https://i/4932caff0ff746eab6f01bf08b70ac45.png"}}}
                    """ : """
                    {"code":0,"data":{"title":"Video title","owner":{"name":"Creator"},"pic":"https://i/cover.jpg",
                    "pages":[{"cid":11,"page":1,"part":"First","duration":10},
                             {"cid":22,"page":2,"part":"Second","duration":20}]}}
                    """;
            return new BilibiliPagelistMetadataResolver.PagelistResponse(200, body);
        }, Clock.fixed(Instant.ofEpochSecond(1702204169), ZoneOffset.UTC));
        var metadata = resolver.resolve("BV1VqsQ6KEoy", 2);
        assertEquals(22L, metadata.cid());
        assertEquals("Video title", metadata.title());
        assertEquals("Creator", metadata.author());
        assertEquals(2, metadata.pages().size());
        assertEquals(30000L, metadata.durationMillis());
        assertFalse(metadata.degraded());
        resolver.resolve("BV1VqsQ6KEoy", 1);
        assertEquals(3, requests.size());
        assertTrue(requests.get(1).contains("/wbi/view?bvid=BV1VqsQ6KEoy&wts=1702204169&w_rid="));
        assertFalse(requests.stream().anyMatch(url -> url.contains("/web-interface/view?")));
    }

    @Test void signingSortsFiltersAndEncodesParameters() {
        assertEquals(BilibiliWbiMetadataResolver.sign(Map.of("foo", "a b", "bar", "114"), "key", 123),
                BilibiliWbiMetadataResolver.sign(Map.of("bar", "114", "foo", "a! b'()*"), "key", 123));
        assertTrue(BilibiliWbiMetadataResolver.sign(Map.of("foo", "a b"), "key", 123)
                .startsWith("foo=a%20b&wts=123&w_rid="));
    }
}
