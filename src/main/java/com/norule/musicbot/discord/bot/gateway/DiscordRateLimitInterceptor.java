package com.norule.musicbot.discord.bot.gateway;

import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Observes every HTTP attempt, including 429s consumed internally by JDA's rate limiter. */
public final class DiscordRateLimitInterceptor implements Interceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordRateLimitInterceptor.class);
    // Only explicitly instrumented endpoints are attributed. Never infer a feature from a channel binding.
    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    private final DiscordBackgroundRestQueue background;
    private long nextWarning = System.nanoTime();
    private int suppressed;

    public DiscordRateLimitInterceptor(DiscordBackgroundRestQueue background) {
        this.background = background;
    }

    public static <T extends RestAction<?>> T track(T action, String feature, boolean background, boolean startup,
                                                  String method, String path) {
        synchronized (SOURCES) {
            long now = System.nanoTime();
            SOURCES.values().removeIf(source -> now - source.createdAt > TimeUnit.MINUTES.toNanos(10));
            if (SOURCES.size() >= 4096) {
                SOURCES.remove(SOURCES.keySet().iterator().next());
            }
            String key = method + " " + path;
            Source existing = SOURCES.get(key);
            // Concurrent operations on the same route cannot be distinguished at the HTTP boundary.
            SOURCES.put(key, new Source(existing == null ? feature : "AMBIGUOUS", background, startup, now));
        }
        return action;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        String path = request.url().encodedPath().replaceFirst("^/api(?:/v[0-9]+)?", "");
        String key = request.method() + " " + path;
        Source source;
        int attempt;
        synchronized (SOURCES) {
            source = SOURCES.get(key);
            attempt = source == null ? 0 : ++source.attempts;
        }
        Response response = chain.proceed(request);
        if (response.code() == 429) {
            long retryMs = retryAfterMillis(response);
            // Admission only: JDA owns global/shared/user bucket handling and all HTTP retries.
            background.pause(retryMs);
            log(response, path, source, attempt, retryMs);
        } else {
            synchronized (SOURCES) {
                if (source != null) {
                    SOURCES.remove(key, source);
                }
            }
        }
        return response;
    }

    private synchronized void log(Response response, String path, Source source, int attempt, long retryMs) {
        long now = System.nanoTime();
        if (now < nextWarning) {
            suppressed++;
            return;
        }
        String scope = response.header("X-RateLimit-Scope", "unknown");
        boolean global = "true".equalsIgnoreCase(response.header("X-RateLimit-Global"))
                || "global".equalsIgnoreCase(scope) || body(response).getBoolean("global", false);
        LOGGER.warn("[RateLimit] httpStatus=429 method={} route={} feature={} priority={} startup={} automaticRetry={} "
                        + "scope={} global={} retryAfterMs={} pendingBackground={} inFlightBackground={} suppressedSinceLast={}",
                response.request().method(), routeTemplate(path), source == null ? "UNATTRIBUTED" : source.feature,
                source == null || "AMBIGUOUS".equals(source.feature) ? "unknown"
                        : source.background ? "BACKGROUND" : source.startup ? "CONTROL" : "USER",
                source == null || "AMBIGUOUS".equals(source.feature) ? "unknown" : source.startup,
                source == null || "AMBIGUOUS".equals(source.feature) ? "unknown" : attempt > 1,
                scope.replace('\r', ' ').replace('\n', ' '), global, retryMs,
                background.pendingCount(), background.inFlightCount(), suppressed);
        suppressed = 0;
        nextWarning = now + TimeUnit.SECONDS.toNanos(10);
    }

    static String routeTemplate(String path) {
        // Never log webhook/interaction tokens, query strings, or literal user data in paths.
        StringBuilder route = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            boolean known = switch (part) {
                case "channels", "guilds", "applications", "commands", "messages", "bulk-delete",
                     "interactions", "webhooks", "callback", "@original", "@me", "users", "members",
                     "roles", "permissions", "reactions", "pins", "threads" -> true;
                default -> false;
            };
            route.append('/').append(known ? part : "{id}");
        }
        return route.toString();
    }

    static long retryAfterMillis(Response response) {
        long retry = -1;
        for (String header : new String[]{"Retry-After", "X-RateLimit-Reset-After"}) {
            retry = Math.max(retry, secondsToMillis(response.header(header)));
        }
        return Math.max(retry, secondsToMillis(String.valueOf(body(response).getDouble("retry_after", -1))));
    }

    private static long secondsToMillis(String seconds) {
        if (seconds == null) return -1;
        try {
            return new BigDecimal(seconds).movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return -1;
        }
    }

    private static DataObject body(Response response) {
        try {
            return DataObject.fromJson(response.peekBody(4096).string());
        } catch (IOException | RuntimeException ignored) {
            // Malformed metadata must not affect JDA's original response processing.
            return DataObject.empty();
        }
    }

    private static final class Source {
        private final String feature;
        private final boolean background;
        private final boolean startup;
        private final long createdAt;
        private int attempts;
        private Source(String feature, boolean background, boolean startup, long createdAt) {
            this.feature = feature;
            this.background = background;
            this.startup = startup;
            this.createdAt = createdAt;
        }
    }
}
