package com.norule.musicbot.discord.bot.gateway.wordchain;

import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;

import java.util.concurrent.CompletableFuture;

public interface DictionaryApiGateway {
    CompletableFuture<DictionaryLookupResult> lookup(String word);
}

