package com.norule.musicbot.web.security;

import com.norule.musicbot.web.session.WebSessionManager;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

public final class CsrfProtectionService {
    public static final String HEADER_NAME = "X-CSRF-Token";

    private static final Set<String> PROTECTED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    public boolean requiresProtection(HttpExchange exchange) {
        if (exchange == null || exchange.getRequestMethod() == null) {
            return false;
        }
        return PROTECTED_METHODS.contains(exchange.getRequestMethod().toUpperCase(Locale.ROOT));
    }

    public boolean validate(HttpExchange exchange, WebSessionManager.WebSession session) {
        if (!requiresProtection(exchange)) {
            return true;
        }
        if (session == null || session.csrfToken == null || session.csrfToken.isBlank()) {
            return false;
        }
        String suppliedToken = exchange.getRequestHeaders().getFirst(HEADER_NAME);
        if (suppliedToken == null || suppliedToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                session.csrfToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
