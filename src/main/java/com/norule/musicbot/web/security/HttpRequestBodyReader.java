package com.norule.musicbot.web.security;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class HttpRequestBodyReader {
    public static final long MAX_SHORT_URL_REQUEST_BODY_BYTES = 16L * 1024L;
    public static final long MAX_DASHBOARD_REQUEST_BODY_BYTES = 256L * 1024L;

    private static final int BUFFER_SIZE = 8192;

    private HttpRequestBodyReader() {
    }

    public static byte[] readBodyLimited(HttpExchange exchange, long maxBytes) throws IOException {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange cannot be null");
        }
        if (maxBytes < 0L || maxBytes > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("maxBytes must be between 0 and Integer.MAX_VALUE - 1");
        }

        long contentLength = validateDeclaredLength(exchange, maxBytes);

        int initialCapacity = contentLength >= 0L
                ? (int) Math.min(contentLength, maxBytes)
                : (int) Math.min(BUFFER_SIZE, maxBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1, initialCapacity));
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        try (InputStream input = exchange.getRequestBody()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    throw new RequestBodyTooLargeException(maxBytes);
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    public static long copyBodyLimited(HttpExchange exchange, OutputStream output,
                                       long maxBytes) throws IOException {
        if (exchange == null || output == null) {
            throw new IllegalArgumentException("exchange and output cannot be null");
        }
        if (maxBytes < 0L) {
            throw new IllegalArgumentException("maxBytes cannot be negative");
        }
        validateDeclaredLength(exchange, maxBytes);
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        try (InputStream input = exchange.getRequestBody()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    throw new RequestBodyTooLargeException(maxBytes);
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    public static long contentLength(HttpExchange exchange) {
        if (exchange == null) {
            return -1L;
        }
        return parseContentLength(exchange.getRequestHeaders().getFirst("Content-Length"));
    }

    /**
     * Validates a declared request size without opening or consuming the request body.
     * A negative result means the request did not declare a usable Content-Length.
     */
    public static long validateDeclaredLength(HttpExchange exchange, long maxBytes)
            throws RequestBodyTooLargeException {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange cannot be null");
        }
        if (maxBytes < 0L) {
            throw new IllegalArgumentException("maxBytes cannot be negative");
        }
        long declaredLength = contentLength(exchange);
        if (declaredLength > maxBytes) {
            throw new RequestBodyTooLargeException(maxBytes);
        }
        return declaredLength;
    }

    public static String readUtf8BodyLimited(HttpExchange exchange, long maxBytes) throws IOException {
        return new String(readBodyLimited(exchange, maxBytes), StandardCharsets.UTF_8);
    }

    private static long parseContentLength(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0L ? -1L : parsed;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    public static final class RequestBodyTooLargeException extends IOException {
        private final long maxBytes;

        public RequestBodyTooLargeException(long maxBytes) {
            super("Request body exceeds " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }
}
