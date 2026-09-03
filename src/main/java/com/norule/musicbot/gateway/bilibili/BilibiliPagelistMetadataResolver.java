package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliMetadata;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.norule.musicbot.domain.music.bilibili.BilibiliVideoIdentifier;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BilibiliPagelistMetadataResolver {
    private static final String PAGELIST_ENDPOINT = "https://api.bilibili.com/x/player/pagelist?bvid=";
    private static final String FALLBACK_AUTHOR = "Bilibili";

    private final PagelistTransport transport;

    public BilibiliPagelistMetadataResolver(HttpInterfaceManager httpInterfaceManager) {
        this(new SharedHttpTransport(httpInterfaceManager));
    }

    BilibiliPagelistMetadataResolver(PagelistTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public BilibiliMetadata resolve(String bvid, Integer requestedPage) throws IOException {
        String normalizedBvid = BilibiliVideoIdentifier.normalizeBvid(bvid);
        if (normalizedBvid.isBlank()) {
            throw new IOException("Bilibili pagelist metadata requires a valid BVID");
        }

        PagelistResponse response = transport.get(URI.create(PAGELIST_ENDPOINT + normalizedBvid));
        validateHttpStatus(response.statusCode());
        JsonBrowser root = JsonBrowser.parse(response.body());
        if (!root.isMap()) {
            throw new IOException("Bilibili pagelist metadata response was not a JSON object");
        }
        int apiCode = root.get("code").asInt(Integer.MIN_VALUE);
        if (apiCode != 0) {
            throw new BilibiliRequestException(
                    BilibiliFailureCategory.BILIBILI_METADATA_FAILED,
                    BilibiliFailureStage.METADATA,
                    200,
                    "Bilibili pagelist metadata API returned code " + apiCode
            );
        }

        JsonBrowser data = root.get("data");
        if (!data.isList() || data.values().isEmpty()) {
            throw new IOException("Bilibili pagelist metadata response did not contain pages");
        }

        List<RawPage> rawPages = new ArrayList<>();
        for (JsonBrowser page : data.values()) {
            rawPages.add(parsePage(page, normalizedBvid));
        }
        rawPages.sort(Comparator.comparingInt(RawPage::number));

        RawPage selected = selectPage(rawPages, requestedPage);
        boolean playlist = rawPages.size() > 1;
        String canonicalUrl = canonicalVideoUrl(normalizedBvid);
        List<BilibiliMetadata.Page> pages = rawPages.stream()
                .map(page -> toMetadataPage(page, normalizedBvid, canonicalUrl, playlist))
                .toList();
        long durationMillis = playlist
                ? pages.stream().mapToLong(BilibiliMetadata.Page::durationMillis).reduce(0L, this::saturatedAdd)
                : selected.durationMillis();

        return new BilibiliMetadata(
                normalizedBvid,
                selected.cid(),
                selected.title(),
                FALLBACK_AUTHOR,
                durationMillis,
                selected.thumbnail(),
                canonicalUrl,
                selected.title(),
                playlist,
                false,
                true,
                selected.number(),
                pages
        );
    }

    private RawPage parsePage(JsonBrowser page, String bvid) throws IOException {
        if (!page.isMap()) {
            throw new IOException("Bilibili pagelist metadata contained an invalid page entry");
        }
        long cid = page.get("cid").asLong(0L);
        int number = page.get("page").asInt(0);
        if (cid <= 0L || number <= 0) {
            throw new IOException("Bilibili pagelist metadata page was missing cid or page");
        }
        String title = page.get("part").safeText().trim();
        if (title.isBlank()) {
            title = bvid + " P" + number;
        }
        long durationSeconds = Math.max(0L, page.get("duration").asLong(0L));
        return new RawPage(
                number,
                cid,
                title,
                saturatedMilliseconds(durationSeconds),
                page.get("first_frame").safeText().trim()
        );
    }

    private RawPage selectPage(List<RawPage> pages, Integer requestedPage) {
        if (requestedPage == null) {
            return pages.stream().filter(page -> page.number() == 1).findFirst().orElse(pages.get(0));
        }
        return pages.stream()
                .filter(page -> page.number() == requestedPage)
                .findFirst()
                .orElseThrow(() -> new BilibiliRequestException(
                        BilibiliFailureCategory.BILIBILI_METADATA_FAILED,
                        BilibiliFailureStage.METADATA,
                        200,
                        "Bilibili pagelist metadata did not contain requested page"
                ));
    }

    private BilibiliMetadata.Page toMetadataPage(RawPage page,
                                                  String bvid,
                                                  String canonicalUrl,
                                                  boolean playlist) {
        String webpageUrl = playlist ? canonicalUrl + "?p=" + page.number() : canonicalUrl;
        return new BilibiliMetadata.Page(
                page.number(),
                page.cid(),
                page.title(),
                FALLBACK_AUTHOR,
                page.durationMillis(),
                page.thumbnail(),
                webpageUrl,
                bvid,
                false,
                "",
                "VIDEO",
                bvid
        );
    }

    private void validateHttpStatus(int statusCode) throws IOException {
        if (statusCode == 200) {
            return;
        }
        BilibiliFailureCategory category = switch (statusCode) {
            case 403 -> BilibiliFailureCategory.BILIBILI_ACCESS_DENIED;
            case 412 -> BilibiliFailureCategory.BILIBILI_RISK_CONTROL;
            case 429 -> BilibiliFailureCategory.BILIBILI_RATE_LIMITED;
            default -> null;
        };
        if (category != null) {
            throw new BilibiliRequestException(
                    category,
                    BilibiliFailureStage.METADATA,
                    statusCode,
                    "Bilibili pagelist metadata request returned HTTP " + statusCode
            );
        }
        throw new IOException("Invalid status code for bilibili pagelist metadata: " + statusCode);
    }

    private long saturatedMilliseconds(long seconds) {
        return seconds > Long.MAX_VALUE / 1_000L ? Long.MAX_VALUE : seconds * 1_000L;
    }

    private long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private String canonicalVideoUrl(String bvid) {
        return "https://www.bilibili.com/video/" + bvid + "/";
    }

    @FunctionalInterface
    interface PagelistTransport {
        PagelistResponse get(URI uri) throws IOException;
    }

    record PagelistResponse(int statusCode, String body) {
        PagelistResponse {
            body = body == null ? "" : body;
        }
    }

    private record RawPage(int number, long cid, String title, long durationMillis, String thumbnail) {
    }

    private static final class SharedHttpTransport implements PagelistTransport {
        private final HttpInterfaceManager httpInterfaceManager;

        private SharedHttpTransport(HttpInterfaceManager httpInterfaceManager) {
            this.httpInterfaceManager = Objects.requireNonNull(httpInterfaceManager, "httpInterfaceManager");
        }

        @Override
        public PagelistResponse get(URI uri) throws IOException {
            try (HttpInterface httpInterface = httpInterfaceManager.getInterface();
                 CloseableHttpResponse response = httpInterface.execute(new HttpGet(uri))) {
                int statusCode = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                String body = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
                return new PagelistResponse(statusCode, body);
            }
        }
    }
}
