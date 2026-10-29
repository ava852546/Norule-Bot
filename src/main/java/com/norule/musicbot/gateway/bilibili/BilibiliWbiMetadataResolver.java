package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliMetadata;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.norule.musicbot.domain.music.bilibili.BilibiliVideoIdentifier;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** WBI signing and metadata I/O belong at the Bilibili boundary. */
final class BilibiliWbiMetadataResolver {
    private static final int[] MIXIN = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
            27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13};
    private final BilibiliPagelistMetadataResolver.PagelistTransport transport;
    private final Clock clock;
    private String key;
    private long keyExpires;

    BilibiliWbiMetadataResolver(HttpInterfaceManager manager) {
        this(new BilibiliPagelistMetadataResolver.SharedHttpTransport(manager), Clock.systemUTC());
    }

    BilibiliWbiMetadataResolver(BilibiliPagelistMetadataResolver.PagelistTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    BilibiliMetadata resolve(String bvid, Integer requestedPage) throws IOException {
        String normalized = BilibiliVideoIdentifier.normalizeBvid(bvid);
        if (normalized.isBlank()) throw new IOException("WBI metadata requires a valid BVID");
        String query = sign(Map.of("bvid", normalized), signingKey(), clock.instant().getEpochSecond());
        JsonBrowser root = get("https://api.bilibili.com/x/web-interface/wbi/view?" + query);
        int code = root.get("code").asInt(Integer.MIN_VALUE);
        if (code != 0) {
            if (code == -403) invalidateKey();
            throw new IOException("Bilibili WBI metadata API returned code " + code);
        }
        JsonBrowser data = root.get("data");
        String title = data.get("title").safeText();
        String author = data.get("owner").get("name").safeText();
        String thumbnail = data.get("pic").safeText();
        String canonical = "https://www.bilibili.com/video/" + normalized + "/";
        var pages = new ArrayList<BilibiliMetadata.Page>();
        for (JsonBrowser page : data.get("pages").values()) {
            int number = page.get("page").asInt(0);
            long cid = page.get("cid").asLong(0L);
            if (number <= 0 || cid <= 0) throw new IOException("Bilibili WBI page missing page or CID");
            long seconds = Math.max(0L, page.get("duration").asLong(0L));
            pages.add(new BilibiliMetadata.Page(number, cid,
                    data.get("pages").values().size() == 1 ? title : page.get("part").safeText(),
                    author, Math.multiplyExact(seconds, 1000L), thumbnail, canonical + "?p=" + number,
                    normalized, false, "", "VIDEO", normalized));
        }
        if (pages.isEmpty() || title.isBlank()) throw new IOException("Bilibili WBI metadata missing title or pages");
        var selected = pages.stream().filter(p -> p.number() == (requestedPage == null ? 1 : requestedPage))
                .findFirst().orElseThrow(() -> new IOException("Bilibili WBI metadata missing requested page"));
        return new BilibiliMetadata(normalized, selected.cid(), title, author,
                pages.stream().mapToLong(BilibiliMetadata.Page::durationMillis).sum(), thumbnail, canonical,
                title, pages.size() > 1, false, false, selected.number(), pages);
    }

    private synchronized String signingKey() throws IOException {
        if (key != null && clock.millis() < keyExpires) return key;
        // nav can return code=-101 to guests while still providing public WBI image keys.
        JsonBrowser images = get("https://api.bilibili.com/x/web-interface/nav").get("data").get("wbi_img");
        String lookup = imageKey(images.get("img_url").safeText()) + imageKey(images.get("sub_url").safeText());
        if (lookup.length() != 64) throw new IOException("Bilibili nav response missing WBI keys");
        StringBuilder mixed = new StringBuilder();
        for (int index : MIXIN) mixed.append(lookup.charAt(index));
        key = mixed.toString();
        keyExpires = clock.millis() + 30_000L;
        return key;
    }

    private synchronized void invalidateKey() { key = null; }

    static String sign(Map<String, String> parameters, String key, long timestamp) {
        var sorted = new TreeMap<>(parameters);
        sorted.put("wts", Long.toString(timestamp));
        String query = sorted.entrySet().stream().map(e -> encode(e.getKey()) + "="
                + encode(e.getValue().replaceAll("[!'()*]", ""))).collect(Collectors.joining("&"));
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest((query + key).getBytes(StandardCharsets.UTF_8)));
            return query + "&w_rid=" + digest;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK missing MD5 for WBI signing", impossible);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String imageKey(String url) throws IOException {
        int start = url.lastIndexOf('/') + 1;
        int end = url.indexOf('.', start);
        if (end <= start) throw new IOException("Invalid Bilibili WBI image key");
        return url.substring(start, end);
    }

    private JsonBrowser get(String url) throws IOException {
        var response = transport.get(URI.create(url));
        if (response.statusCode() != 200) {
            throw new BilibiliRequestException(switch (response.statusCode()) {
                case 403 -> BilibiliFailureCategory.BILIBILI_ACCESS_DENIED;
                case 412 -> BilibiliFailureCategory.BILIBILI_RISK_CONTROL;
                case 429 -> BilibiliFailureCategory.BILIBILI_RATE_LIMITED;
                default -> BilibiliFailureCategory.BILIBILI_METADATA_FAILED;
            }, BilibiliFailureStage.METADATA, response.statusCode(),
                    "Bilibili WBI metadata HTTP " + response.statusCode());
        }
        return JsonBrowser.parse(response.body());
    }
}
