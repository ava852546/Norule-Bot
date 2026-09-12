package com.norule.musicbot.discord.bot.gateway.wordchain;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;
import com.norule.musicbot.domain.wordchain.FreeDictionaryCircuitBreaker;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class FreeDictionaryApiGateway implements DictionaryApiGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(FreeDictionaryApiGateway.class);
    private static final String PROVIDER = "FREE_DICTIONARY";
    private static final String USER_AGENT = "NoRule-Bot/1.0";
    private static final long MAX_RETRY_DELAY_MILLIS = 2_000L;

    private final HttpClient httpClient;
    private final boolean enabled;
    private final String endpoint;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final long initialDelayMillis;
    private final Duration circuitCooldown;
    private final Clock clock;
    private final FreeDictionaryCircuitBreaker circuitBreaker;

    public FreeDictionaryApiGateway(
            HttpClient httpClient,
            BotConfig.Dictionary.FreeDictionary config,
            Duration requestTimeout
    ) {
        this(
                httpClient,
                config == null || config.isEnabled(),
                config == null ? BotConfig.Dictionary.FreeDictionary.DEFAULT_ENDPOINT : config.getEndpoint(),
                requestTimeout,
                config == null || config.getRetry().isEnabled(),
                config == null
                        ? BotConfig.Dictionary.FreeDictionary.Retry.DEFAULT_MAX_ATTEMPTS
                        : config.getRetry().getMaxAttempts(),
                config == null
                        ? BotConfig.Dictionary.FreeDictionary.Retry.DEFAULT_INITIAL_DELAY_MILLIS
                        : config.getRetry().getInitialDelayMillis(),
                config == null || config.getCircuitBreaker().isEnabled(),
                config == null
                        ? BotConfig.Dictionary.FreeDictionary.CircuitBreaker.DEFAULT_FAILURE_THRESHOLD
                        : config.getCircuitBreaker().getFailureThreshold(),
                Duration.ofSeconds(config == null
                        ? BotConfig.Dictionary.FreeDictionary.CircuitBreaker.DEFAULT_COOLDOWN_SECONDS
                        : config.getCircuitBreaker().getCooldownSeconds()),
                Clock.systemUTC()
        );
    }

    public FreeDictionaryApiGateway(HttpClient httpClient, String endpoint, Duration requestTimeout) {
        this(
                httpClient,
                true,
                endpoint,
                requestTimeout,
                true,
                BotConfig.Dictionary.FreeDictionary.Retry.DEFAULT_MAX_ATTEMPTS,
                BotConfig.Dictionary.FreeDictionary.Retry.DEFAULT_INITIAL_DELAY_MILLIS,
                true,
                BotConfig.Dictionary.FreeDictionary.CircuitBreaker.DEFAULT_FAILURE_THRESHOLD,
                Duration.ofSeconds(
                        BotConfig.Dictionary.FreeDictionary.CircuitBreaker.DEFAULT_COOLDOWN_SECONDS
                ),
                Clock.systemUTC()
        );
    }

    FreeDictionaryApiGateway(
            HttpClient httpClient,
            boolean enabled,
            String endpoint,
            Duration requestTimeout,
            boolean retryEnabled,
            int maxAttempts,
            long initialDelayMillis,
            boolean circuitBreakerEnabled,
            int failureThreshold,
            Duration circuitCooldown,
            Clock clock
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.enabled = enabled;
        this.endpoint = normalizeEndpoint(endpoint, BotConfig.Dictionary.FreeDictionary.DEFAULT_ENDPOINT);
        this.requestTimeout = positiveDuration(
                requestTimeout,
                Duration.ofSeconds(BotConfig.Dictionary.DEFAULT_REQUEST_TIMEOUT_SECONDS)
        );
        this.maxAttempts = retryEnabled ? Math.max(1, maxAttempts) : 1;
        this.initialDelayMillis = Math.max(0L, initialDelayMillis);
        this.circuitCooldown = positiveDuration(circuitCooldown, Duration.ofSeconds(60));
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.circuitBreaker = new FreeDictionaryCircuitBreaker(
                circuitBreakerEnabled,
                failureThreshold,
                this.circuitCooldown,
                this.clock
        );
    }

    @Override
    public CompletableFuture<DictionaryLookupResult> lookup(String word) {
        String safeWord = normalizeWord(word);
        if (!enabled || safeWord.isBlank()) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.NOT_FOUND);
        }
        if (!circuitBreaker.tryAcquirePermission()) {
            LOGGER.debug(
                    "[NoRule] Dictionary request skipped: provider={} reason=CIRCUIT_OPEN",
                    PROVIDER
            );
            return CompletableFuture.completedFuture(DictionaryLookupResult.API_ERROR);
        }
        return executeRequest(safeWord, 1);
    }

    private CompletableFuture<DictionaryLookupResult> executeRequest(String word, int attempt) {
        long startedAtNanos = System.nanoTime();
        HttpRequest request;
        try {
            String encodedWord = URLEncoder.encode(word, StandardCharsets.UTF_8);
            request = HttpRequest.newBuilder(URI.create(endpoint + encodedWord))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
        } catch (RuntimeException error) {
            recordCircuitHealthy();
            logFailure(word, "REQUEST_BUILD", 0, attempt, elapsedMillis(startedAtNanos));
            return CompletableFuture.completedFuture(DictionaryLookupResult.API_ERROR);
        }

        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> resolveAttempt(
                            word,
                            attempt,
                            startedAtNanos,
                            response,
                            error
                    ))
                    .thenCompose(next -> next);
        } catch (RuntimeException error) {
            return handleRequestFailure(word, attempt, startedAtNanos, error);
        }
    }

    private CompletableFuture<DictionaryLookupResult> resolveAttempt(
            String word,
            int attempt,
            long startedAtNanos,
            HttpResponse<String> response,
            Throwable error
    ) {
        if (error != null) {
            return handleRequestFailure(word, attempt, startedAtNanos, error);
        }
        if (response == null) {
            return handleTransientFailure(
                    word,
                    attempt,
                    "MISSING_RESPONSE",
                    0,
                    elapsedMillis(startedAtNanos),
                    retryDelayMillis(attempt)
            );
        }

        int status = response.statusCode();
        long durationMillis = elapsedMillis(startedAtNanos);
        if (isRetryableStatus(status)) {
            long delayMillis = status == 429
                    ? rateLimitDelayMillis(response, attempt)
                    : retryDelayMillis(attempt);
            if (status == 429) {
                LOGGER.warn(
                        "[NoRule] Dictionary request rate limited: provider={} word={} status=429 "
                                + "retryAfterSeconds={} attempt={}/{} durationMs={}",
                        PROVIDER,
                        word,
                        formatSeconds(delayMillis),
                        attempt,
                        maxAttempts,
                        durationMillis
                );
            } else {
                logFailure(word, "HTTP_" + status, status, attempt, durationMillis);
            }
            return retryOrComplete(word, attempt, delayMillis);
        }

        recordCircuitHealthy();
        if (status == 404) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.NOT_FOUND);
        }
        if (status < 200 || status >= 300) {
            logFailure(word, "HTTP_" + status, status, attempt, durationMillis);
            return CompletableFuture.completedFuture(DictionaryLookupResult.API_ERROR);
        }

        try {
            DataArray entries = DataArray.fromJson(response.body());
            if (entries.isEmpty()) {
                return CompletableFuture.completedFuture(DictionaryLookupResult.NOT_FOUND);
            }
            DictionaryLookupResult result = entries.isType(0, DataType.OBJECT)
                    ? DictionaryLookupResult.FOUND
                    : DictionaryLookupResult.API_ERROR;
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException parseFailure) {
            logFailure(word, "INVALID_JSON", status, attempt, durationMillis);
            return CompletableFuture.completedFuture(DictionaryLookupResult.API_ERROR);
        }
    }

    private CompletableFuture<DictionaryLookupResult> handleRequestFailure(
            String word,
            int attempt,
            long startedAtNanos,
            Throwable error
    ) {
        Throwable cause = unwrap(error);
        long durationMillis = elapsedMillis(startedAtNanos);
        if (isTransientFailure(cause)) {
            return handleTransientFailure(
                    word,
                    attempt,
                    failureCategory(cause),
                    0,
                    durationMillis,
                    retryDelayMillis(attempt)
            );
        }
        recordCircuitHealthy();
        logFailure(word, failureCategory(cause), 0, attempt, durationMillis);
        return CompletableFuture.completedFuture(DictionaryLookupResult.API_ERROR);
    }

    private CompletableFuture<DictionaryLookupResult> handleTransientFailure(
            String word,
            int attempt,
            String category,
            int status,
            long durationMillis,
            long delayMillis
    ) {
        logFailure(word, category, status, attempt, durationMillis);
        return retryOrComplete(word, attempt, delayMillis);
    }

    private CompletableFuture<DictionaryLookupResult> retryOrComplete(
            String word,
            int attempt,
            long delayMillis
    ) {
        if (attempt < maxAttempts) {
            return retryAfter(word, attempt + 1, delayMillis);
        }
        recordCircuitFailure();
        return CompletableFuture.completedFuture(DictionaryLookupResult.API_ERROR);
    }

    private CompletableFuture<DictionaryLookupResult> retryAfter(
            String word,
            int nextAttempt,
            long delayMillis
    ) {
        CompletableFuture<DictionaryLookupResult> delayed = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(
                Math.max(0L, Math.min(MAX_RETRY_DELAY_MILLIS, delayMillis)),
                TimeUnit.MILLISECONDS
        ).execute(() -> executeRequest(word, nextAttempt).whenComplete((result, error) -> {
            if (error == null) {
                delayed.complete(result);
            } else {
                delayed.complete(DictionaryLookupResult.API_ERROR);
            }
        }));
        return delayed;
    }

    private void recordCircuitFailure() {
        FreeDictionaryCircuitBreaker.Transition transition = circuitBreaker.recordFailure();
        if (transition == FreeDictionaryCircuitBreaker.Transition.OPENED
                || transition == FreeDictionaryCircuitBreaker.Transition.REOPENED) {
            LOGGER.warn(
                    "[NoRule] Dictionary circuit opened: provider={} failures={} cooldownSeconds={}",
                    PROVIDER,
                    circuitBreaker.failureCount(),
                    circuitCooldown.toSeconds()
            );
        }
    }

    private void recordCircuitHealthy() {
        if (circuitBreaker.recordSuccess() == FreeDictionaryCircuitBreaker.Transition.RECOVERED) {
            LOGGER.info("[NoRule] Dictionary circuit recovered: provider={}", PROVIDER);
        }
    }

    private void logFailure(
            String word,
            String category,
            int status,
            int attempt,
            long durationMillis
    ) {
        if (status > 0) {
            LOGGER.warn(
                    "[NoRule] Dictionary request failed: provider={} word={} category={} status={} "
                            + "attempt={}/{} durationMs={}",
                    PROVIDER,
                    word,
                    category,
                    status,
                    attempt,
                    maxAttempts,
                    durationMillis
            );
            return;
        }
        LOGGER.warn(
                "[NoRule] Dictionary request failed: provider={} word={} category={} "
                        + "attempt={}/{} durationMs={}",
                PROVIDER,
                word,
                category,
                attempt,
                maxAttempts,
                durationMillis
        );
    }

    private long rateLimitDelayMillis(HttpResponse<String> response, int attempt) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        long parsed = parseRetryAfterMillis(retryAfter);
        return parsed >= 0L
                ? Math.min(MAX_RETRY_DELAY_MILLIS, parsed)
                : retryDelayMillis(attempt);
    }

    private long parseRetryAfterMillis(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        String normalized = value.trim();
        try {
            long seconds = Long.parseLong(normalized);
            if (seconds < 0L) {
                return -1L;
            }
            return seconds >= Long.MAX_VALUE / 1_000L
                    ? Long.MAX_VALUE
                    : seconds * 1_000L;
        } catch (NumberFormatException ignored) {
            try {
                long delay = ZonedDateTime.parse(normalized)
                        .toInstant()
                        .toEpochMilli() - clock.millis();
                return Math.max(0L, delay);
            } catch (DateTimeParseException parseFailure) {
                return -1L;
            }
        }
    }

    private long retryDelayMillis(int failedAttempt) {
        long delay = Math.min(MAX_RETRY_DELAY_MILLIS, initialDelayMillis);
        for (int current = 1; current < failedAttempt && delay < MAX_RETRY_DELAY_MILLIS; current++) {
            delay = Math.min(MAX_RETRY_DELAY_MILLIS, delay * 2L);
        }
        return delay;
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429
                || status == 500
                || status == 502
                || status == 503
                || status == 504;
    }

    private static boolean isTransientFailure(Throwable error) {
        return error instanceof HttpTimeoutException
                || error instanceof ConnectException
                || error instanceof IOException;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String failureCategory(Throwable error) {
        if (error instanceof HttpTimeoutException) {
            return "TIMEOUT";
        }
        if (error instanceof ConnectException) {
            return "CONNECT";
        }
        if (error instanceof IOException) {
            return "IO";
        }
        return error == null ? "UNKNOWN" : error.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    private static String formatSeconds(long delayMillis) {
        if (delayMillis % 1_000L == 0L) {
            return Long.toString(delayMillis / 1_000L);
        }
        return String.format(Locale.ROOT, "%.3f", delayMillis / 1_000D);
    }

    private static String normalizeWord(String word) {
        return word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeEndpoint(String endpoint, String defaultEndpoint) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        if (normalized.isBlank()) {
            return defaultEndpoint;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static Duration positiveDuration(Duration value, Duration defaultValue) {
        return value == null || value.isZero() || value.isNegative() ? defaultValue : value;
    }
}
