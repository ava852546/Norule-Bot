package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestRateLimiter;
import com.norule.musicbot.domain.music.bilibili.BilibiliSingleFlight;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Resolves only b23 redirects; metadata and video identifier parsing belong to the caller. */
public final class BilibiliShortUrlResolver {
    private static final int MAX_REDIRECTS = 5;
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private final RedirectTransport transport;
    private final Clock clock;
    private final int maxEntries;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75F, true);
    private final BilibiliSingleFlight<URI> singleFlight = new BilibiliSingleFlight<>();
    // Redirect traffic has its own budget; it must not consume the metadata/pagelist API burst.
    private final BilibiliRequestRateLimiter rateLimiter;

    public BilibiliShortUrlResolver() {
        this(BilibiliShortUrlResolver::request, Clock.systemUTC(), 1000);
    }

    BilibiliShortUrlResolver(RedirectTransport transport, Clock clock, int maxEntries) {
        this.transport = transport;
        this.clock = clock;
        this.maxEntries = Math.max(1, maxEntries);
        this.rateLimiter = new BilibiliRequestRateLimiter(true, 2, 10, clock);
    }

    public static boolean isShortUrl(String input) {
        try {
            return input != null && isShortHost(URI.create(input.trim()).getHost());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public URI resolve(String input) {
        URI initial = parse(input);
        validate(initial, true);
        String key = initial.toASCIIString();
        try {
            URI cached = cached(key);
            if (cached != null) {
                return cached;
            }
            return singleFlight.execute(key, Duration.ofSeconds(90), () -> {
                URI existing = cached(key);
                if (existing != null) {
                    return existing;
                }
                URI resolved = follow(initial);
                remember(key, resolved);
                return resolved;
            });
        } catch (BilibiliRequestException failure) {
            throw failure;
        } catch (InterruptedIOException | TimeoutException timeout) {
            throw failure("TIMEOUT", 0, true);
        } catch (UnknownHostException dns) {
            throw failure("DNS_FAILURE", 0, true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("INTERRUPTED", 0, false);
        } catch (IOException network) {
            // Do not retain transport exception messages that may contain tracking query tokens.
            throw failure("NETWORK_FAILURE", 0, true);
        } catch (RuntimeException unexpected) {
            throw unexpected;
        } catch (Exception unexpected) {
            throw new IllegalStateException("Unexpected Bilibili short URL resolution failure", unexpected);
        }
    }

    private URI follow(URI initial) throws IOException {
        URI current = initial;
        Set<URI> visited = new HashSet<>();
        for (int redirects = 0; ; redirects++) {
            validate(current, false);
            if (!visited.add(current)) {
                throw failure("REDIRECT_LOOP", 0, false);
            }
            // Never fetch the final web page. The adapter extracts BVID/page and calls the API.
            if (!isShortHost(current.getHost())) {
                return current;
            }
            if (redirects >= MAX_REDIRECTS) {
                throw failure("TOO_MANY_REDIRECTS", 0, false);
            }
            RedirectResponse response = send(current, true);
            if (response.statusCode() == 405 || response.statusCode() == 501
                    || response.statusCode() == 200) {
                response = send(current, false);
            }
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                throw failure(status >= 400 ? "HTTP_ERROR" : "MISSING_REDIRECT", status, status >= 500);
            }
            if (response.location() == null || response.location().isBlank()) {
                throw failure("INVALID_LOCATION", status, false);
            }
            try {
                current = normalize(current.resolve(new URI(response.location().trim())));
            } catch (IllegalArgumentException | URISyntaxException invalid) {
                throw failure("INVALID_LOCATION", status, false);
            }
        }
    }

    private RedirectResponse send(URI uri, boolean head) throws IOException {
        if (!rateLimiter.tryAcquire()) {
            throw new BilibiliRequestException(BilibiliFailureCategory.BILIBILI_RATE_LIMITED,
                    BilibiliFailureStage.SHORT_URL_RESOLVE, 0,
                    "Bilibili short URL resolution failed: LOCAL_RATE_LIMIT", true, "", null);
        }
        return transport.request(uri, head);
    }

    private static URI parse(String input) {
        try {
            return normalize(new URI(input == null ? "" : input.trim()));
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            throw failure("INVALID_URL", 0, false);
        }
    }

    private static URI normalize(URI uri) throws URISyntaxException {
        // Strip fragments, but preserve raw query and path escaping without double encoding.
        String value = uri.toASCIIString();
        int fragment = value.indexOf('#');
        return new URI(fragment < 0 ? value : value.substring(0, fragment)).normalize();
    }

    private static void validate(URI uri, boolean initial) {
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != ("https".equalsIgnoreCase(scheme) ? 443 : 80))) {
            throw failure("FORBIDDEN_URL", 0, false);
        }
        String host = uri.getHost();
        if (!isShortHost(host) && (initial || !matchesHost(host, "bilibili.com"))) {
            throw failure("FORBIDDEN_HOST", 0, false);
        }
    }

    private static boolean isShortHost(String host) {
        return matchesHost(host, "b23.tv");
    }

    private static boolean matchesHost(String host, String domain) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return domain.equals(normalized) || normalized.endsWith("." + domain);
    }

    private synchronized URI cached(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            cache.remove(key);
            return null;
        }
        return entry.uri();
    }

    private synchronized void remember(String key, URI resolved) {
        cleanupExpired();
        cache.put(key, new CacheEntry(resolved, clock.instant().plus(CACHE_TTL)));
        while (cache.size() > maxEntries) {
            cache.remove(cache.keySet().iterator().next());
        }
    }

    public synchronized void cleanupExpired() {
        Instant now = clock.instant();
        cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
    }

    int participantCount() {
        return singleFlight.activeParticipantCount();
    }

    static BilibiliRequestException failure(String reason, int status, boolean retryable) {
        BilibiliFailureCategory category = switch (status) {
            case 403 -> BilibiliFailureCategory.BILIBILI_ACCESS_DENIED;
            case 412 -> BilibiliFailureCategory.BILIBILI_RISK_CONTROL;
            case 429 -> BilibiliFailureCategory.BILIBILI_RATE_LIMITED;
            default -> BilibiliFailureCategory.BILIBILI_METADATA_FAILED;
        };
        return new BilibiliRequestException(category, BilibiliFailureStage.SHORT_URL_RESOLVE, status,
                "Bilibili short URL resolution failed: " + reason, retryable, "", null);
    }

    private static RedirectResponse request(URI uri, boolean head) throws IOException {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(3000).setConnectionRequestTimeout(3000).setSocketTimeout(5000)
                .setRedirectsEnabled(false).build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement()
                // These exact checked addresses are used for the connection: no second DNS lookup.
                .setDnsResolver(host -> validateAddresses(InetAddress.getAllByName(host)))
                .build()) {
            HttpRequestBase request = head ? new HttpHead(uri) : new HttpGet(uri);
            request.setHeader("User-Agent", "Mozilla/5.0");
            try (CloseableHttpResponse response = client.execute(request)) {
                Header location = response.getFirstHeader("Location");
                // Abort before closing so a GET fallback never drains a full response body.
                request.abort();
                return new RedirectResponse(response.getStatusLine().getStatusCode(),
                        location == null ? null : location.getValue());
            } finally {
                request.abort();
            }
        }
    }

    static InetAddress[] validateAddresses(InetAddress[] addresses) {
        if (addresses.length == 0) {
            throw failure("DNS_FAILURE", 0, true);
        }
        for (InetAddress address : addresses) {
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            boolean forbidden = address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress();
            if (bytes.length == 4) {
                forbidden |= first == 0 || first == 10 || first == 127 || first >= 224
                        || (first == 100 && second >= 64 && second <= 127)
                        || (first == 169 && second == 254)
                        || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && (second == 168 || second == 0))
                        || (first == 198 && (second == 18 || second == 19 || second == 51))
                        || (first == 203 && second == 0);
            } else {
                // Only global unicast; exclude translation/tunneling and documentation ranges.
                forbidden |= (first & 0xe0) != 0x20 || (first == 0x20 && second == 0x02)
                        || (first == 0x20 && second == 0x01
                        && (((bytes[2] & 255) == 0 && (bytes[3] & 255) == 0)
                        || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)));
            }
            if (forbidden) {
                throw failure("FORBIDDEN_ADDRESS", 0, false);
            }
        }
        return addresses;
    }

    @FunctionalInterface
    interface RedirectTransport {
        RedirectResponse request(URI uri, boolean head) throws IOException;
    }

    record RedirectResponse(int statusCode, String location) { }

    private record CacheEntry(URI uri, Instant expiresAt) { }
}
