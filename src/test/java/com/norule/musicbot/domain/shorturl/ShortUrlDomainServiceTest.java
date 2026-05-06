package com.norule.musicbot.domain.shorturl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlDomainServiceTest {
    private final ShortUrlDomainService domain = new ShortUrlDomainService();

    @Test
    void validatesHttpAndHttpsOnly() {
        assertTrue(domain.isValidTarget("https://example.com/path"));
        assertTrue(domain.isValidTarget("http://example.com/path"));
        assertFalse(domain.isValidTarget("javascript:alert(1)"));
        assertFalse(domain.isValidTarget("data:text/plain,hello"));
        assertFalse(domain.isValidTarget("file:///tmp/1.txt"));
        assertFalse(domain.isValidTarget("ftp://example.com"));
        assertFalse(domain.isValidTarget("chrome://settings"));
        assertFalse(domain.isValidTarget("about:blank"));
    }

    @Test
    void validatesSlugCharsetAndReservedCodes() {
        assertTrue(domain.isValidSlug("Abc_123-x"));
        assertFalse(domain.isValidSlug("bad/code"));
        assertFalse(domain.isValidSlug("bad code"));
        assertTrue(domain.isReservedCode("api"));
        assertTrue(domain.isReservedCode("INDEX"));
        assertFalse(domain.isReservedCode("custom-page"));
    }

    @Test
    void detectsPrivateAndLocalTargets() {
        assertTrue(domain.isPrivateOrLocalTarget("https://localhost/test"));
        assertTrue(domain.isPrivateOrLocalTarget("https://127.0.0.1/test"));
        assertTrue(domain.isPrivateOrLocalTarget("https://10.1.2.3/test"));
        assertTrue(domain.isPrivateOrLocalTarget("https://192.168.1.20/test"));
        assertTrue(domain.isPrivateOrLocalTarget("https://[::1]/test"));
        assertFalse(domain.isPrivateOrLocalTarget("https://example.com/test"));
        assertFalse(domain.isPrivateOrLocalTarget("https://8.8.8.8/test"));
    }
}
