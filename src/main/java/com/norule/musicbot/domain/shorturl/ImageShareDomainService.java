package com.norule.musicbot.domain.shorturl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class ImageShareDomainService {
    private static final DateTimeFormatter DEFAULT_PASSWORD_FORMAT = DateTimeFormatter.ofPattern("MMdd");

    public record MediaType(String contentType, String extension, boolean video, long durationMillis) {
    }

    public MediaType detectMediaType(byte[] content) {
        if (content == null || content.length < 3) {
            return null;
        }
        if (isPng(content)) {
            return new MediaType("image/png", "png", false, 0L);
        }
        if (isJpeg(content)) {
            return new MediaType("image/jpeg", "jpg", false, 0L);
        }
        if (isGif(content)) {
            return new MediaType("image/gif", "gif", false, 0L);
        }
        if (isWebp(content)) {
            return new MediaType("image/webp", "webp", false, 0L);
        }
        long mp4Duration = Mp4VideoMetadataParser.readDurationMillis(content);
        if (mp4Duration > 0L) {
            return new MediaType("video/mp4", "mp4", true, mp4Duration);
        }
        long webmDuration = WebmVideoMetadataParser.readDurationMillis(content);
        if (webmDuration > 0L) {
            return new MediaType("video/webm", "webm", true, webmDuration);
        }
        return null;
    }

    /** Detects a stored file's signature without requiring complete video duration metadata. */
    public MediaType detectStoredMediaType(byte[] header) {
        MediaType detected = detectMediaType(header);
        if (detected != null || header == null) return detected;
        if (header.length >= 12 && header[4] == 'f' && header[5] == 't'
                && header[6] == 'y' && header[7] == 'p') {
            return new MediaType("video/mp4", "mp4", true, 0L);
        }
        if (header.length >= 4 && header[0] == 0x1a && header[1] == 0x45
                && header[2] == (byte) 0xdf && header[3] == (byte) 0xa3) {
            return new MediaType("video/webm", "webm", true, 0L);
        }
        return null;
    }

    public String normalizePassword(String password) {
        return password == null ? "" : password.trim();
    }

    public boolean isValidPassword(String password) {
        return isValidPassword(password, 4, 128);
    }

    public boolean isValidPassword(String password, int minimumLength, int maximumLength) {
        int length = password == null ? 0 : password.length();
        int safeMinimum = Math.max(1, minimumLength);
        int safeMaximum = Math.max(safeMinimum, maximumLength);
        return length >= safeMinimum && length <= safeMaximum;
    }

    public String defaultPassword(LocalDate date) {
        LocalDate effectiveDate = date == null ? LocalDate.now() : date;
        return DEFAULT_PASSWORD_FORMAT.format(effectiveDate);
    }

    private boolean isPng(byte[] content) {
        return content.length >= 8
                && content[0] == (byte) 0x89
                && content[1] == 0x50
                && content[2] == 0x4E
                && content[3] == 0x47
                && content[4] == 0x0D
                && content[5] == 0x0A
                && content[6] == 0x1A
                && content[7] == 0x0A;
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3
                && content[0] == (byte) 0xFF
                && content[1] == (byte) 0xD8
                && content[2] == (byte) 0xFF;
    }

    private boolean isGif(byte[] content) {
        if (content.length < 6) {
            return false;
        }
        String header = new String(content, 0, 6, java.nio.charset.StandardCharsets.US_ASCII)
                .toUpperCase(Locale.ROOT);
        return "GIF87A".equals(header) || "GIF89A".equals(header);
    }

    private boolean isWebp(byte[] content) {
        return content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P';
    }
}
