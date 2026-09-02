package com.norule.musicbot.web.security;

import com.norule.musicbot.web.TestHttpExchange;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientAddressResolverTest {
    @Test
    void ignoresForwardedHeaderFromUntrustedRemoteAddress() {
        TestHttpExchange exchange = exchange("203.0.113.10")
                .header("X-Forwarded-For", "198.51.100.99");

        assertEquals("203.0.113.10", ClientAddressResolver.resolve(
                exchange, List.of("127.0.0.1/32")));
    }

    @Test
    void acceptsForwardedAddressOnlyFromConfiguredTrustedProxy() {
        TestHttpExchange exchange = exchange("127.0.0.1")
                .header("X-Forwarded-For", "198.51.100.99");

        assertEquals("198.51.100.99", ClientAddressResolver.resolve(
                exchange, List.of("127.0.0.1/32")));
    }

    @Test
    void walksTrustedProxyChainFromRightToLeft() {
        TestHttpExchange exchange = exchange("127.0.0.1")
                .header("X-Forwarded-For", "198.51.100.77, 10.0.0.5");

        assertEquals("198.51.100.77", ClientAddressResolver.resolve(
                exchange, List.of("127.0.0.1/32", "10.0.0.0/8")));
    }

    @Test
    void ignoresSpoofedLeftmostEntryBeyondFirstUntrustedHop() {
        TestHttpExchange exchange = exchange("127.0.0.1")
                .header("X-Forwarded-For", "192.0.2.66, 198.51.100.77");

        assertEquals("198.51.100.77", ClientAddressResolver.resolve(
                exchange, List.of("127.0.0.1/32")));
    }

    @Test
    void malformedForwardedHeaderFallsBackToSocketAddress() {
        TestHttpExchange exchange = exchange("127.0.0.1")
                .header("X-Forwarded-For", "attacker.example");

        assertEquals("127.0.0.1", ClientAddressResolver.resolve(
                exchange, List.of("127.0.0.1/32")));
    }

    private TestHttpExchange exchange(String address) {
        return new TestHttpExchange("POST", "/api/short", new byte[0],
                new InetSocketAddress(address, 54321));
    }
}
