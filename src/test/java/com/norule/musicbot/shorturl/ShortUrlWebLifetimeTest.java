package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShortUrlWebLifetimeTest {
    @Test
    void expirationPolicyOnlyChangesNewAnonymousUrlsAndDoesNotRewriteExistingRows(@TempDir Path dir) {
        var repository = new SqliteShortUrlRepository(dir.resolve("lifetime.db"));
        var service = new ShortUrlService(repository,
                new ShortUrlService.Options(false, 60_000, 60_000, "https://s.example.com", 7, false));
        var existing = service.create("https://example.com/legacy", "legacy");
        var permanent = service.createFromWebWithOutcome("https://example.com/permanent", "", "", "ip").entry();
        assertEquals(Long.MAX_VALUE, permanent.expiresAt());
        service.updateOptions(new ShortUrlService.Options(false, 60_000, 60_000,
                "https://s.example.com", 7, false, true, 30));
        var anonymous = service.createFromWebWithOutcome("https://example.com/anonymous", "", "", "ip").entry();
        var owned = service.createFromWebWithOutcome("https://example.com/owned", "owned", "a", "ip").entry();
        assertEquals(30 * 86_400_000L, anonymous.expiresAt() - anonymous.createdAt());
        assertEquals(Long.MAX_VALUE, owned.expiresAt());
        assertEquals(existing.expiresAt(), repository.findByCode("legacy").expiresAt());
        assertEquals(Long.MAX_VALUE, repository.findByCode(permanent.code()).expiresAt());
        assertEquals(1, repository.countByOwnerUserId("a", true, System.currentTimeMillis()));
        assertFalse(repository.deleteOwned("owned", "b", owned.createdAt()));
        assertFalse(repository.updateOwnedTarget("owned", "a", owned.createdAt() + 1, "https://example.com/changed"));
        assertEquals("https://example.com/owned", repository.findByCode("owned").target());
    }
}
