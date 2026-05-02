package com.norule.musicbot.discord.bot.ops.ticket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TicketTokenService {
    private final Map<String, Long> expiryByToken = new ConcurrentHashMap<>();

    public String issue(long ttlMillis) {
        String token = UUID.randomUUID().toString().replace("-", "");
        expiryByToken.put(token, System.currentTimeMillis() + Math.max(1000L, ttlMillis));
        return token;
    }

    public boolean isExpired(String token) {
        Long expiry = expiryByToken.get(token);
        return expiry == null || expiry < System.currentTimeMillis();
    }

    public void revoke(String token) {
        if (token != null) {
            expiryByToken.remove(token);
        }
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        expiryByToken.entrySet().removeIf(e -> e.getValue() < now);
    }
}
