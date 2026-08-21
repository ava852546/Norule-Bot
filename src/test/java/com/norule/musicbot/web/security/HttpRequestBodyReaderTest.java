package com.norule.musicbot.web.security;

import com.norule.musicbot.web.TestHttpExchange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpRequestBodyReaderTest {
    private static final long LIMIT = HttpRequestBodyReader.MAX_SHORT_URL_REQUEST_BODY_BYTES;

    @Test
    void readsSmallJsonAndFormBodies() throws Exception {
        byte[] json = "{\"url\":\"https://example.com\"}".getBytes(StandardCharsets.UTF_8);
        byte[] form = "url=https%3A%2F%2Fexample.com&slug=test".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(json, HttpRequestBodyReader.readBodyLimited(
                new TestHttpExchange("POST", "/api/short", json), LIMIT));
        assertEquals(new String(form, StandardCharsets.UTF_8), HttpRequestBodyReader.readUtf8BodyLimited(
                new TestHttpExchange("POST", "/api/short", form), LIMIT));
    }

    @Test
    void rejectsOversizedContentLengthBeforeOpeningTheStream() {
        TestHttpExchange exchange = new TestHttpExchange("POST", "/api/short", new byte[0])
                .header("Content-Length", String.valueOf(LIMIT + 1L));

        assertThrows(HttpRequestBodyReader.RequestBodyTooLargeException.class,
                () -> HttpRequestBodyReader.readBodyLimited(exchange, LIMIT));
        assertFalse(exchange.requestBodyOpened());
    }

    @Test
    void enforcesTheLimitWithoutContentLength() throws Exception {
        byte[] exact = new byte[(int) LIMIT];
        Arrays.fill(exact, (byte) 'a');
        byte[] oversized = Arrays.copyOf(exact, exact.length + 1);

        assertEquals(LIMIT, HttpRequestBodyReader.readBodyLimited(
                new TestHttpExchange("POST", "/api/short", exact), LIMIT).length);
        assertThrows(HttpRequestBodyReader.RequestBodyTooLargeException.class,
                () -> HttpRequestBodyReader.readBodyLimited(
                        new TestHttpExchange("POST", "/api/short", oversized), LIMIT));
    }
}
