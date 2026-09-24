package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.domain.shorturl.*;
import com.norule.musicbot.shorturl.*;
import com.norule.musicbot.shorturl.infra.FileSystemImageShareStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class MediaRetentionLifecycleTest {
    @TempDir Path directory;
    private static final long DAY = 86_400_000L;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10};

    @Test
    void oneDayPublicPlusThirtyDaysArchiveRetainsCodeAfterPurgeAndRestart() throws Exception {
        Fixture f = new Fixture();
        ImageShare share = f.upload("a");
        String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG)) + ".png";
        assertEquals(name, share.storageName());
        f.clock.advance(DAY - 1);
        f.service.cleanupExpired();
        assertNotNull(f.service.resolve(share.code()));
        f.clock.advance(1);
        f.service.cleanupExpired();
        long archivedAt = f.blobs.findById(share.blobId()).archivedAt();
        assertNull(f.service.resolve(share.code()));
        assertNotNull(f.service.findExpired(share.code()));
        assertFalse(Files.exists(f.active.resolve(name)));
        assertTrue(Files.exists(f.archive.resolve(name)));
        f.clock.advance(30 * DAY - 1);
        f.service.cleanupExpired();
        assertEquals(archivedAt, f.blobs.findById(share.blobId()).archivedAt());
        assertTrue(Files.exists(f.archive.resolve(name)));
        f.clock.advance(1);
        f.service.cleanupExpired();
        assertFalse(Files.exists(f.archive.resolve(name)));
        assertNull(f.blobs.findById(share.blobId()));
        assertEquals(0, f.shares.findByCode(share.code()).blobId());
        assertTrue(f.service.isCodeInUse(share.code()));
        f.restart();
        assertNotNull(f.service.findExpired(share.code()));
        assertNull(f.service.open(f.shares.findByCode(share.code())));
        assertEquals(1, f.service.countByOwnerUserId("a"));
        assertEquals(ShortUrlService.MutationResult.SUCCESS, f.service.deleteOwned(share.code(), "a"));
        assertFalse(f.service.isCodeInUse(share.code()));
    }

    @Test
    void ownerDeletionProtectsSharedBlobAndUsesActualArchiveTime() {
        Fixture f = new Fixture();
        ImageShare a = f.upload("a");
        ImageShare b = f.upload("b");
        assertEquals(a.blobId(), b.blobId());
        assertNotEquals(a.code(), b.code());
        assertEquals(ShortUrlService.MutationResult.UNAUTHORIZED, f.service.deleteOwned(a.code(), ""));
        assertEquals(ShortUrlService.MutationResult.FORBIDDEN, f.service.deleteOwned(a.code(), "b"));
        assertEquals(ShortUrlService.MutationResult.NOT_FOUND, f.service.deleteOwned("missing", "a"));
        f.clock.advance(DAY / 4);
        assertEquals(ShortUrlService.MutationResult.SUCCESS, f.service.deleteOwned(a.code(), "a"));
        assertNotNull(f.service.resolve(b.code()));
        assertTrue(Files.exists(f.active.resolve(a.storageName())));
        assertFalse(Files.exists(f.archive.resolve(a.storageName())));
        assertEquals(ShortUrlService.MutationResult.SUCCESS, f.service.deleteOwned(b.code(), "b"));
        assertEquals(f.clock.millis(), f.blobs.findById(b.blobId()).archivedAt());
        f.clock.advance(6 * 60_000L);
        f.service.cleanupExpired();
        assertTrue(Files.exists(f.archive.resolve(a.storageName())));
        f.clock.advance(30 * DAY);
        f.service.cleanupExpired();
        assertNull(f.blobs.findById(b.blobId()));
    }

    @Test
    void deletingExpiredMappingDoesNotExtendArchiveRetention() {
        Fixture f = new Fixture();
        ImageShare share = f.upload("a");
        f.clock.advance(DAY);
        f.service.cleanupExpired();
        long archivedAt = f.blobs.findById(share.blobId()).archivedAt();
        f.clock.advance(4 * DAY);
        f.service.deleteOwned(share.code(), "a");
        assertEquals(archivedAt, f.blobs.findById(share.blobId()).archivedAt());
        f.clock.advance(26 * DAY);
        f.service.cleanupExpired();
        assertNull(f.blobs.findById(share.blobId()));
    }

    @Test
    void reactivationReusesBlobAndStartsNewRetentionOnlyOnNextArchive() {
        Fixture f = new Fixture();
        ImageShare original = f.upload("a");
        f.clock.advance(DAY);
        f.service.cleanupExpired();
        f.clock.advance(20 * DAY);
        ImageShare again = f.upload("b");
        assertEquals(original.blobId(), again.blobId());
        assertEquals(0, f.blobs.findById(again.blobId()).archivedAt());
        assertTrue(Files.exists(f.active.resolve(again.storageName())));
        assertFalse(Files.exists(f.archive.resolve(again.storageName())));
        assertNotNull(f.service.findExpired(original.code()));
        f.clock.advance(DAY);
        f.service.cleanupExpired();
        assertEquals(f.clock.millis(), f.blobs.findById(again.blobId()).archivedAt());
    }

    @Test
    void expiredUrlRetainsCodeUntilOwnerDeletion() {
        Fixture f = new Fixture();
        long now = System.currentTimeMillis();
        f.urls.save(new ShortUrlService.ShortUrlEntry("expired", "https://example.com/private", now - DAY, now - 1, 0, "a", 0));
        ShortUrlService core = new ShortUrlService(f.urls);
        core.cleanupExpired();
        assertNull(core.resolve("expired"));
        assertTrue(core.isExpiredShortUrl("expired"));
        assertTrue(core.resourceExists("expired"));
        assertNull(core.create("https://example.com/new", "EXPIRED"));
        assertEquals(ShortUrlService.MutationResult.FORBIDDEN, core.mutateOwned("expired", "b", "", true));
        assertEquals(ShortUrlService.MutationResult.SUCCESS, core.mutateOwned("expired", "a", "", true));
        assertNotNull(core.create("https://example.com/new", "expired"));
    }

    @Test
    void migratesLegacyActiveAndArchivedNamesWithoutResettingRetention() throws Exception {
        Fixture f = new Fixture();
        Files.createDirectories(f.active);
        Files.write(f.active.resolve("old.png"), PNG);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG));
        MediaBlob old = f.blobs.saveIfAbsent(new MediaBlob(0, hash, "old.png", PNG.length,
                "image/png", "png", f.clock.millis(), MediaStorageState.ACTIVE, "", 0));
        f.shares.save(new ImageShare("legacy", "old.png", "image/png", PNG.length,
                f.clock.millis(), f.clock.millis() + DAY, "", hash, 0,
                MediaStorageState.ACTIVE, "", 0, MediaOwnerType.DISCORD_USER, "a", "a", "", "", 0, old.id()));
        f.restart();
        assertEquals(hash + ".png", f.shares.findByCode("legacy").storageName());
        assertFalse(Files.exists(f.active.resolve("old.png")));
        assertArrayEquals(PNG, Files.readAllBytes(f.active.resolve(hash + ".png")));
        f.clock.advance(DAY);
        f.service.cleanupExpired();
        long archivedAt = f.blobs.findById(old.id()).archivedAt();
        Files.move(f.archive.resolve(hash + ".png"), f.archive.resolve("archive-old.png"));
        f.blobs.update(new MediaBlob(old.id(), hash, "archive-old.png", PNG.length,
                "image/png", "png", old.createdAt(), MediaStorageState.ARCHIVED, "archive-old.png", archivedAt));
        f.clock.advance(DAY);
        f.service.cleanupExpired();
        assertEquals(archivedAt, f.blobs.findById(old.id()).archivedAt());
        assertFalse(Files.exists(f.active.resolve(hash + ".png")));
        assertTrue(Files.exists(f.archive.resolve(hash + ".png")));
    }

    private final class Fixture {
        final MutableClock clock = new MutableClock();
        final Path active = directory.resolve("active");
        final Path archive = directory.resolve("archive");
        final Path database = directory.resolve("test.db");
        final SqliteShortUrlRepository urls = new SqliteShortUrlRepository(database);
        final SqliteImageShareRepository shares = new SqliteImageShareRepository(database);
        final SqliteMediaBlobRepository blobs = new SqliteMediaBlobRepository(database);
        ImageShareService service;
        Fixture() { restart(); }
        void restart() {
            service = new ImageShareService(shares, blobs, urls,
                    new FileSystemImageShareStorage(active, directory.resolve("uploads"), archive),
                    new ImageShareService.Options(true, DAY, 365 * DAY, 1024, 1024, 300_000,
                            30 * DAY, 60_000, 7), clock, null);
            service.cleanupExpired();
        }
        ImageShare upload(String owner) {
            var result = service.create(new ImageShareService.Upload(PNG, false, "", DAY),
                    new QuotaSubject(AccessTier.AUTHENTICATED, owner, MediaOwnerType.DISCORD_USER, owner, "", ""));
            assertTrue(result.isSuccess(), String.valueOf(result.error()));
            return result.imageShare();
        }
    }

    private static final class MutableClock extends Clock {
        long now = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
        void advance(long millis) { now += millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
