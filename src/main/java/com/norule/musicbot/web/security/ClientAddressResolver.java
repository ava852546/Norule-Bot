package com.norule.musicbot.web.security;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetSocketAddress;

public final class ClientAddressResolver {
    private static final int MAX_ADDRESS_LENGTH = 128;

    private ClientAddressResolver() {
    }

    public static String resolve(HttpExchange exchange) {
        if (exchange == null) {
            return "unknown";
        }
        InetSocketAddress remoteAddress = exchange.getRemoteAddress();
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && isTrustedLocalProxy(remoteAddress)) {
            return normalize(forwarded.split(",", 2)[0]);
        }
        return remoteAddress == null || remoteAddress.getAddress() == null
                ? "unknown"
                : normalize(remoteAddress.getAddress().getHostAddress());
    }

    private static boolean isTrustedLocalProxy(InetSocketAddress remoteAddress) {
        // TODO: Replace local/private proxy trust with configurable trusted proxy CIDRs.
        return remoteAddress != null && remoteAddress.getAddress() != null
                && (remoteAddress.getAddress().isLoopbackAddress()
                || remoteAddress.getAddress().isSiteLocalAddress());
    }

    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String normalized = address.trim();
        return normalized.length() <= MAX_ADDRESS_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ADDRESS_LENGTH);
    }
}
