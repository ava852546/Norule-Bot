package com.norule.musicbot.discord.bot.gateway.wordchain;

import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;
import net.dv8tion.jda.api.utils.data.DataArray;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

public final class DictionaryApiHttpGateway implements DictionaryApiGateway {
    private static final String ENDPOINT = "https://api.dictionaryapi.dev/api/v2/entries/en/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;

    public DictionaryApiHttpGateway() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    public DictionaryApiHttpGateway(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public CompletableFuture<DictionaryLookupResult> lookup(String word) {
        String safeWord = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        if (safeWord.isBlank()) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.NOT_FOUND);
        }
        String encoded = URLEncoder.encode(safeWord, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + encoded))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    if (error != null || response == null) {
                        return DictionaryLookupResult.API_ERROR;
                    }
                    int status = response.statusCode();
                    if (status == 404) {
                        return DictionaryLookupResult.NOT_FOUND;
                    }
                    if (status == 429 || status >= 500 || status < 200 || status >= 300) {
                        return DictionaryLookupResult.API_ERROR;
                    }
                    try {
                        DataArray parsed = DataArray.fromJson(response.body());
                        return parsed == null ? DictionaryLookupResult.API_ERROR : DictionaryLookupResult.FOUND;
                    } catch (Exception ignored) {
                        return DictionaryLookupResult.API_ERROR;
                    }
                });
    }
}
