package com.norule.musicbot.discord.bot.service.wordchain;

import com.norule.musicbot.discord.bot.gateway.wordchain.DictionaryApiGateway;
import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class DictionaryApiService {
    private final DictionaryApiGateway gateway;
    private final Set<String> validWordCache = ConcurrentHashMap.newKeySet();
    private final Set<String> invalidWordCache = ConcurrentHashMap.newKeySet();

    public DictionaryApiService(DictionaryApiGateway gateway) {
        if (gateway == null) {
            throw new IllegalArgumentException("gateway cannot be null");
        }
        this.gateway = gateway;
    }

    public CompletableFuture<DictionaryLookupResult> lookupWord(String word) {
        String normalized = normalize(word);
        if (normalized.isBlank()) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.NOT_FOUND);
        }
        if (validWordCache.contains(normalized)) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.FOUND);
        }
        if (invalidWordCache.contains(normalized)) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.NOT_FOUND);
        }
        return gateway.lookup(normalized).thenApply(result -> {
            if (result == DictionaryLookupResult.FOUND) {
                validWordCache.add(normalized);
            } else if (result == DictionaryLookupResult.NOT_FOUND) {
                invalidWordCache.add(normalized);
            }
            return result;
        });
    }

    private String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }
}

