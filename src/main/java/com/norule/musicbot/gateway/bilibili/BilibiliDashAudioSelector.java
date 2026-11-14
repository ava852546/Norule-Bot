package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Select only profiles verified with the existing MP4/AAC decoder. Never mix representations. */
final class BilibiliDashAudioSelector {
    private BilibiliDashAudioSelector() { }

    record Selection(String codec, List<URI> urls, String diagnostics) { }

    static Selection select(JsonBrowser data, String videoId, Long cid, int httpStatus, Integer apiCode) {
        JsonBrowser dash = data.get("dash");
        JsonBrowser audio = dash.get("audio");
        String diagnostics = diagnostics(dash, audio, cid, httpStatus, apiCode);
        if (!data.isMap() || !dash.isMap() || (!audio.isNull() && !audio.isList())
                || audio.values().stream().anyMatch(entry -> !entry.isMap())) {
            throw failure(BilibiliFailureCategory.AUDIO_RESPONSE_INVALID, videoId, diagnostics);
        }
        if (audio.values().isEmpty()) {
            throw failure(BilibiliFailureCategory.DASH_AUDIO_EMPTY, videoId, diagnostics);
        }
        var compatible = new ArrayList<>(audio.values());
        compatible.removeIf(entry -> priority(entry) < 0);
        compatible.sort(Comparator.comparingInt(BilibiliDashAudioSelector::priority)
                .thenComparing(Comparator.comparingLong((JsonBrowser entry) -> entry.get("bandwidth").asLong(0L)).reversed()));
        if (compatible.isEmpty()) {
            throw failure(BilibiliFailureCategory.AUDIO_FORMAT_UNSUPPORTED, videoId, diagnostics);
        }
        for (var entry : compatible) {
            List<URI> urls = urls(entry);
            if (!urls.isEmpty()) return new Selection(codec(entry), urls, diagnostics);
        }
        throw failure(BilibiliFailureCategory.AUDIO_URL_MISSING, videoId, diagnostics);
    }

    private static int priority(JsonBrowser entry) {
        String mime = entry.get("mimeType").textOrDefault(entry.get("mime_type").safeText()).trim();
        if (!mime.isEmpty() && !"audio/mp4".equalsIgnoreCase(mime)) return -1;
        return switch (codec(entry)) {
            case "mp4a.40.2" -> 0;
            // Verified with LavaPlayer 2.2.7 MpegAudioTrack, including native decode and seek.
            case "mp4a.40.5" -> 1;
            default -> -1;
        };
    }

    private static String codec(JsonBrowser entry) {
        return entry.get("codecs").safeText().trim().toLowerCase(Locale.ROOT);
    }

    private static List<URI> urls(JsonBrowser entry) {
        var urls = new LinkedHashSet<URI>();
        for (String key : List.of("baseUrl", "base_url")) addUrl(urls, entry.get(key).safeText());
        for (String key : List.of("backupUrl", "backup_url")) {
            if (entry.get(key).isList()) {
                for (var backup : entry.get(key).values()) addUrl(urls, backup.safeText());
            }
        }
        return List.copyOf(urls);
    }

    private static void addUrl(LinkedHashSet<URI> urls, String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                urls.add(uri);
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed candidate must not discard valid backups of the same representation.
        }
    }

    private static String diagnostics(JsonBrowser dash, JsonBrowser audio, Long cid, int status, Integer code) {
        int aacLc = 0;
        int aacLcWithUrl = 0;
        var formats = new ArrayList<String>();
        var codecs = new LinkedHashSet<String>();
        for (var entry : audio.values()) {
            String codec = codec(entry);
            if ("mp4a.40.2".equals(codec)) {
                aacLc++;
                if (!urls(entry).isEmpty()) aacLcWithUrl++;
            }
            if (codecs.size() < 32) codecs.add(safeLabel(codec));
            if (formats.size() >= 32) continue;
            formats.add("{id=" + entry.get("id").asLong(-1L) + ",codecs=" + safeLabel(codec)
                    + ",mimeType=" + safeLabel(entry.get("mimeType").textOrDefault(entry.get("mime_type").safeText()))
                    + ",bandwidth=" + entry.get("bandwidth").asLong(-1L)
                    + ",baseUrl=" + !entry.get("baseUrl").safeText().isBlank()
                    + ",base_url=" + !entry.get("base_url").safeText().isBlank()
                    + ",backupUrl=" + !entry.get("backupUrl").isNull()
                    + ",backup_url=" + !entry.get("backup_url").isNull()
                    + ",validUrlCount=" + urls(entry).size() + "}");
        }
        return "cid=" + (cid == null ? "UNKNOWN" : cid)
                + " playUrlHttpStatus=" + (status == 0 ? "UNKNOWN" : status)
                + " playUrlApiCode=" + (code == null ? "UNKNOWN" : code)
                + " dashPresent=" + !dash.isNull() + " audioPresent=" + !audio.isNull()
                + " audioFormatsCount=" + (audio.isList() ? audio.values().size() : "UNKNOWN")
                + " availableCodecs=" + codecs + " aacLcCount=" + aacLc
                + " aacLcWithValidUrl=" + aacLcWithUrl + " alternativeFormatsCount=" + (audio.values().size() - aacLc)
                + " flacPresent=" + !dash.get("flac").isNull() + " dolbyPresent=" + !dash.get("dolby").isNull()
                + " formats=" + formats;
    }

    private static String safeLabel(String value) {
        // Do not reflect arbitrary API strings (including URLs, tokens or line breaks) into logs.
        return value.matches("[A-Za-z0-9._+/-]{1,64}") ? value : "UNKNOWN";
    }

    private static BilibiliRequestException failure(BilibiliFailureCategory category, String id, String diagnostics) {
        return new BilibiliRequestException(category, BilibiliFailureStage.AUDIO_SELECTION, 0,
                "Bilibili DASH audio selection: category=" + category + " " + diagnostics, false, id, null);
    }
}
