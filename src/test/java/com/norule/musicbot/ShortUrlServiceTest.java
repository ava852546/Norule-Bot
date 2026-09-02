package com.norule.musicbot;

import com.norule.musicbot.domain.shorturl.ShortUrlAccessEvent;
import com.norule.musicbot.domain.shorturl.ShortUrlCreationError;
import com.norule.musicbot.domain.shorturl.ShortUrlStatistics;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    void normalizesAndValidatesNewCustomCodes() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(repository);

        assertEquals("abc", service.create("https://example.com/abc", "  AbC  ").getCode());
        assertNotNull(service.create("https://example.com/dash", "hello-world"));
        assertNotNull(service.create("https://example.com/underscore", "hello_world"));
        assertNotNull(service.create("https://example.com/max", "a".repeat(32)));

        for (String invalid : List.of("a", "ab", "a".repeat(33), "hello world", "hello!",
                "hello.test", "hello/test", "中文", "emoji-😀")) {
            ShortUrlService.CreationOutcome outcome = service.createWithOutcome(
                    "https://example.com/invalid/" + invalid.hashCode(), invalid, "", "");
            assertNull(outcome.entry());
            assertEquals(ShortUrlCreationError.INVALID_CUSTOM_CODE, outcome.error());
        }
    }

    @Test
    void rejectsReservedAndCaseInsensitiveDuplicateCustomCodes() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(repository);

        for (String reserved : List.of("admin", "ADMIN", "Stats", "LOGIN", "api")) {
            ShortUrlService.CreationOutcome outcome = service.createWithOutcome(
                    "https://example.com/reserved/" + reserved, reserved, "", "");
            assertNull(outcome.entry());
            assertEquals(ShortUrlCreationError.RESERVED_CUSTOM_CODE, outcome.error());
        }

        assertNotNull(service.create("https://example.com/first", "NoRule"));
        ShortUrlService.CreationOutcome duplicate = service.createWithOutcome(
                "https://example.com/second", "NORULE", "", "");
        assertNull(duplicate.entry());
        assertEquals(ShortUrlCreationError.CUSTOM_CODE_ALREADY_EXISTS, duplicate.error());
        assertEquals(1, repository.store.size());
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

    @Test
    void recordsViewsAndPersistsDiscordLogChannel() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(repository);
        ShortUrlService.ShortUrlEntry created = service.create("https://example.com/view", "view-code");
        List<ShortUrlAccessEvent> events = new ArrayList<>();
        service.updateLogChannelId(123456789L);
        service.updateAccessPublisher((channelId, event) -> {
            assertEquals(123456789L, channelId);
            events.add(event);
        });

        ShortUrlService.ShortUrlEntry viewed = service.recordView(created, "127.0.0.1", "JUnit");

        assertEquals(1L, viewed.getViewCount());
        assertEquals(123456789L, service.getLogChannelId());
        assertEquals(123456789L, repository.findLogChannelId());
        assertEquals(1, events.size());
        assertEquals(ShortUrlAccessEvent.Action.VIEWED, events.get(0).action());
        assertEquals(1L, events.get(0).viewCount());
    }

    @Test
    void recordsDiscordCreatorAndWebClientAddressForCreationLogs() {
        ShortUrlService service = new ShortUrlService(new InMemoryRepository());
        List<ShortUrlAccessEvent> events = new ArrayList<>();
        service.updateLogChannelId(123456789L);
        service.updateAccessPublisher((channelId, event) -> events.add(event));

        service.create("https://example.com/discord", "discord-code", "123456789012345678", "");
        service.create("https://example.com/web", "web-code", "", "198.51.100.42");

        assertEquals(2, events.size());
        assertEquals("123456789012345678", events.get(0).creatorDiscordUserId());
        assertEquals("", events.get(0).clientAddress());
        assertEquals("", events.get(1).creatorDiscordUserId());
        assertEquals("198.51.100.42", events.get(1).clientAddress());
    }

    @Test
    void exposesStatisticsOnlyToTheRecordedOwner() {
        InMemoryRepository repository = new InMemoryRepository();
        ShortUrlService service = new ShortUrlService(repository);
        ShortUrlService.ShortUrlEntry owned = service.create(
                "https://example.com/private-stats", "owned-code", "owner-a", "127.0.0.1");
        ShortUrlService.ShortUrlEntry anonymous = service.create(
                "https://example.com/anonymous", "anonymous-code", "", "127.0.0.1");

        service.recordView(owned, "127.0.0.1", "JUnit");

        ShortUrlStatistics statistics = service.findStatisticsForOwner("owned-code", "owner-a");
        assertNotNull(statistics);
        assertEquals(1L, statistics.viewCount());
        assertTrue(statistics.lastAccessedAt() > 0L);
        assertNull(service.findStatisticsForOwner("owned-code", "owner-b"));
        assertNull(service.findStatisticsForOwner("anonymous-code", "owner-a"));
        assertNotNull(anonymous);
    }

    private static final class InMemoryRepository implements ShortUrlRepository {
        private final Map<String, ShortUrlService.ShortUrlEntry> store = new LinkedHashMap<>();
        private Long logChannelId;

        @Override
        public ShortUrlService.ShortUrlEntry findByCode(String code) {
            return store.get(code);
        }

        @Override
        public ShortUrlService.ShortUrlEntry findByCodeIgnoreCase(String code) {
            return store.values().stream()
                    .filter(entry -> entry.code().equalsIgnoreCase(code))
                    .findFirst()
                    .orElse(null);
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

        @Override
        public long incrementViewCount(String code) {
            ShortUrlService.ShortUrlEntry entry = store.get(code);
            if (entry == null) {
                return 0L;
            }
            long updated = entry.getViewCount() + 1L;
            store.put(code, entry.withViewCount(updated));
            return updated;
        }

        @Override
        public long incrementViewCount(String code, long lastAccessedAt) {
            ShortUrlService.ShortUrlEntry entry = store.get(code);
            if (entry == null) {
                return 0L;
            }
            long updated = entry.getViewCount() + 1L;
            store.put(code, entry.withViewMetrics(updated, lastAccessedAt));
            return updated;
        }

        @Override
        public Long findLogChannelId() {
            return logChannelId;
        }

        @Override
        public void saveLogChannelId(Long channelId) {
            logChannelId = channelId;
        }
    }
}
