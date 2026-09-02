package com.norule.musicbot.web.security;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.List;

public final class ClientAddressResolver {
    private static final int MAX_ADDRESS_LENGTH = 128;

    private ClientAddressResolver() {
    }

    public static String resolve(HttpExchange exchange) {
        return resolve(exchange, List.of("127.0.0.1/32", "::1/128"));
    }

    public static String resolve(HttpExchange exchange, List<String> trustedProxyCidrs) {
        if (exchange == null) {
            return "unknown";
        }
        InetSocketAddress remoteAddress = exchange.getRemoteAddress();
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()
                && isTrustedProxy(remoteAddress, trustedProxyCidrs)) {
            String parsed = resolveForwardedChain(
                    remoteAddress == null ? null : remoteAddress.getAddress(),
                    forwarded, trustedProxyCidrs);
            if (parsed != null) {
                return parsed;
            }
        }
        return remoteAddress == null || remoteAddress.getAddress() == null
                ? "unknown"
                : normalize(remoteAddress.getAddress().getHostAddress());
    }

    private static boolean isTrustedProxy(InetSocketAddress remoteAddress,
                                          List<String> trustedProxyCidrs) {
        if (remoteAddress == null || remoteAddress.getAddress() == null
                || trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()) {
            return false;
        }
        return isTrustedProxy(remoteAddress.getAddress(), trustedProxyCidrs);
    }

    private static boolean isTrustedProxy(InetAddress address,
                                          List<String> trustedProxyCidrs) {
        if (address == null || trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()) {
            return false;
        }
        for (String cidr : trustedProxyCidrs) {
            if (matchesCidr(address.getAddress(), cidr)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveForwardedChain(InetAddress remoteAddress,
                                                String forwarded,
                                                List<String> trustedProxyCidrs) {
        InetAddress currentHop = remoteAddress;
        String resolved = null;
        String[] hops = forwarded.split(",");
        for (int index = hops.length - 1; index >= 0; index--) {
            if (!isTrustedProxy(currentHop, trustedProxyCidrs)) {
                break;
            }
            String candidate = parseForwardedAddress(hops[index]);
            if (candidate == null) {
                return null;
            }
            try {
                currentHop = InetAddress.getByName(candidate);
                resolved = candidate;
            } catch (Exception ignored) {
                return null;
            }
        }
        return resolved;
    }

    private static boolean matchesCidr(byte[] address, String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return false;
        }
        String[] parts = cidr.trim().split("/", 2);
        try {
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (network.length != address.length) {
                return false;
            }
            int prefix = parts.length == 1 ? network.length * 8 : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > network.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String parseForwardedAddress(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank() || !candidate.matches("[0-9A-Fa-f:.]{2,64}")) {
            return null;
        }
        try {
            return normalize(InetAddress.getByName(candidate).getHostAddress());
        } catch (Exception ignored) {
            return null;
        }
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
