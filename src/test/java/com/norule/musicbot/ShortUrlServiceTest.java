package com.norule.musicbot;

import com.norule.musicbot.shorturl.ShortUrlRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlServiceTest {

    @Test
    void generatesConfiguredLengthWithoutAmbiguousCharacters() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, false)
        );

        ShortUrlService.ShortUrlEntry created = service.create("https://example.com/path");
        assertNotNull(created);
        assertEquals(7, created.getCode().length());
        assertTrue(created.getCode().chars().noneMatch(ch -> "0Oo1lI".indexOf(ch) >= 0));
    }

    @Test
    void skipsDedupeWhenCustomCodeProvided() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, false)
        );

        ShortUrlService.ShortUrlEntry first = service.create("https://example.com/a", "alpha_1");
        ShortUrlService.ShortUrlEntry second = service.create("https://example.com/a", "beta-2");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("alpha_1", first.getCode());
        assertEquals("beta-2", second.getCode());
    }

    @Test
    void dedupesWhenCustomCodeIsEmpty() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, false)
        );

        ShortUrlService.ShortUrlEntry first = service.create("https://example.com/dup");
        ShortUrlService.ShortUrlEntry second = service.create("https://example.com/dup");
        assertSame(first, second);
    }

    @Test
    void rejectsReservedOrInvalidCustomCode() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, false)
        );

        assertNull(service.create("https://example.com", "api"));
        assertNull(service.create("https://example.com", "bad/code"));
    }

    @Test
    void blocksPrivateTargetsWhenDisabledAndAllowsWhenEnabled() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService blockedService = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, false)
        );
        ShortUrlService allowedService = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, true)
        );

        assertNull(blockedService.create("https://127.0.0.1:8443/a"));
        assertNotNull(allowedService.create("https://127.0.0.1:8443/a"));
    }

    @Test
    void blocksSelfDomainTarget() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, false)
        );
        assertNull(service.create("https://s.norule.me/abc123"));
    }

    @Test
    void expiresEntriesOnResolve() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(
                repository,
                new ShortUrlService.Options(true, 7L * 24L * 60L * 60L * 1000L, 60_000L, "https://s.norule.me", 7, false)
        );

        ShortUrlService.ShortUrlEntry created = service.create("https://example.com/ttl", "ttl-code", 1L);
        assertNotNull(created);
        Thread.sleep(5L);
        assertNull(service.resolve("ttl-code"));
    }

    private static final class InMemoryRepository implements ShortUrlRepository {
        private final Map<String, ShortUrlService.ShortUrlEntry> store = new LinkedHashMap<>();

        @Override
        public ShortUrlService.ShortUrlEntry findByCode(String code) {
            return store.get(code);
        }

        @Override
        public ShortUrlService.ShortUrlEntry findActiveByTarget(String target, long nowMillis) {
            ShortUrlService.ShortUrlEntry latest = null;
            for (ShortUrlService.ShortUrlEntry entry : store.values()) {
                if (!entry.getTarget().equals(target)) {
                    continue;
                }
                if (entry.getExpiresAt() <= nowMillis) {
                    continue;
                }
                if (latest == null || entry.getCreatedAt() > latest.getCreatedAt()) {
                    latest = entry;
                }
            }
            return latest;
        }

        @Override
        public void save(ShortUrlService.ShortUrlEntry entry) {
            store.put(entry.getCode(), entry);
        }

        @Override
        public void deleteByCode(String code) {
            store.remove(code);
        }

        @Override
        public int cleanupExpired(long nowMillis) {
            int before = store.size();
            store.entrySet().removeIf(e -> e.getValue().getExpiresAt() <= nowMillis);
            return before - store.size();
        }
    }
}
