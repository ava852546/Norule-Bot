package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.domain.shorturl.AccessTier;
import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaBlob;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import com.norule.musicbot.domain.shorturl.MediaStorageState;
import com.norule.musicbot.domain.shorturl.QuotaSubject;
import com.norule.musicbot.shorturl.SqliteImageShareRepository;
import com.norule.musicbot.shorturl.SqliteMediaBlobRepository;
import com.norule.musicbot.shorturl.SqliteMediaSecurityRepository;
import com.norule.musicbot.shorturl.SqliteShortUrlRepository;
import com.norule.musicbot.shorturl.infra.FileSystemImageShareStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaDeduplicationIntegrationTest {
    private static final byte[] PNG_A = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01
    };
    private static final byte[] PNG_B = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x02
    };

    @TempDir
    Path tempDir;

    @Test
    void deduplicatesBytesAndKeepsSharesPerOwner() throws Exception {
        Fixture fixture = fixture("dedup.db");
        QuotaSubject ownerA = owner("user-a");
        QuotaSubject ownerB = owner("user-b");

        ImageShare first = upload(fixture.service(), PNG_A, ownerA);
        ImageShare sameOwner = upload(fixture.service(), PNG_A.clone(), ownerA);
        ImageShare otherOwner = upload(fixture.service(), PNG_A.clone(), ownerB);
        ImageShare sameSizeDifferentBytes = upload(fixture.service(), PNG_B, ownerA);

        assertEquals(first.code(), sameOwner.code());
        assertEquals(first.blobId(), otherOwner.blobId());
        assertNotEquals(first.code(), otherOwner.code());
        assertNotEquals(first.blobId(), sameSizeDifferentBytes.blobId());
        assertEquals(2L, count(fixture.database(), "media_blobs"));
        assertEquals(3L, count(fixture.database(), "short_url_images"));
        assertEquals(2L, regularFileCount(fixture.activeDirectory()));
    }

    @Test
    void concurrentSameOwnerUploadCreatesOneBlobAndOneShare() throws Exception {
        Fixture fixture = fixture("concurrent.db");
        ImageShareService secondService = service(
                fixture.database(), fixture.storage(), fixture.clock());
        QuotaSubject owner = owner("concurrent-user");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ImageShare> first = executor.submit(
                    () -> concurrentUpload(fixture.service(), owner, ready, start));
            Future<ImageShare> second = executor.submit(
                    () -> concurrentUpload(secondService, owner, ready, start));
            ready.await();
            start.countDown();

            ImageShare firstShare = first.get();
            ImageShare secondShare = second.get();
            assertEquals(firstShare.code(), secondShare.code());
            assertEquals(firstShare.blobId(), secondShare.blobId());
            assertEquals(1L, count(fixture.database(), "media_blobs"));
            assertEquals(1L, count(fixture.database(), "short_url_images"));
            assertEquals(1L, regularFileCount(fixture.activeDirectory()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void blobIsRetainedUntilItsLastShareIsDeleted() throws Exception {
        Fixture fixture = fixture("cleanup.db");
        ImageShare first = upload(fixture.service(), PNG_A, owner("owner-a"));
        ImageShare second = upload(fixture.service(), PNG_A, owner("owner-b"));

        fixture.images().deleteByCode(first.code());
        fixture.service().cleanupExpired();
        assertEquals(1L, count(fixture.database(), "media_blobs"));
        assertTrue(Files.isRegularFile(fixture.activeDirectory().resolve(first.storageName())));

        fixture.images().deleteByCode(second.code());
        fixture.clock().advanceMillis(5L * 60L * 1000L + 1L);
        fixture.service().cleanupExpired();
        assertEquals(0L, count(fixture.database(), "media_blobs"));
        assertFalse(Files.exists(fixture.activeDirectory().resolve(first.storageName())));
    }

    @Test
    void migrationMergesDuplicateFilesAndPreservesShareMetadataWhenRerun() throws Exception {
        Path database = tempDir.resolve("migration.db");
        Path active = tempDir.resolve("migration-active");
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                active, tempDir.resolve("migration-tmp"), tempDir.resolve("migration-archive"));
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        long now = Instant.parse("2026-09-02T08:00:00Z").toEpochMilli();
        long expiresAt = now + 60L * 60L * 1000L;
        ImageShare first = legacyShare("legacyA", "cat.png", "user-a", 17L, now, expiresAt);
        ImageShare second = legacyShare("legacyB", "cat-copy.png", "user-b", 23L, now, expiresAt);
        images.save(first);
        images.save(second);
        Files.createDirectories(active);
        Files.write(active.resolve(first.storageName()), PNG_A);
        Files.write(active.resolve(second.storageName()), PNG_A);

        MutableClock clock = new MutableClock(Instant.ofEpochMilli(now));
        service(database, storage, clock);

        ImageShare migratedFirst = images.findByCode(first.code());
        ImageShare migratedSecond = images.findByCode(second.code());
        assertEquals(migratedFirst.blobId(), migratedSecond.blobId());
        assertEquals("legacyA", migratedFirst.code());
        assertEquals("legacyB", migratedSecond.code());
        assertEquals(17L, migratedFirst.viewCount());
        assertEquals(23L, migratedSecond.viewCount());
        assertEquals("user-a", migratedFirst.ownerId());
        assertEquals("user-b", migratedSecond.ownerId());
        assertEquals(expiresAt, migratedFirst.expiresAt());
        assertEquals(expiresAt, migratedSecond.expiresAt());
        assertEquals(1L, count(database, "media_blobs"));
        assertEquals(1L, regularFileCount(active));

        service(database, storage, clock);
        assertEquals(1L, count(database, "media_blobs"));
        assertEquals(2L, count(database, "short_url_images"));
        assertEquals(1L, regularFileCount(active));
    }

    @Test
    void quotasCountPhysicalBlobOnceAndLogicalBytesForEveryOwnersShare() throws Exception {
        Path database = tempDir.resolve("quota-dedup.db");
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                tempDir.resolve("quota-active"), tempDir.resolve("quota-tmp"),
                tempDir.resolve("quota-archive"));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-02T08:00:00Z"));
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaBlobRepository blobs = new SqliteMediaBlobRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        MediaQuotaService.TierLimits exactFileLimit = new MediaQuotaService.TierLimits(
                100, 100, PNG_A.length, 24L * 60L * 60L * 1000L);
        MediaQuotaService quota = new MediaQuotaService(security,
                new MediaQuotaService.Options(true, exactFileLimit, exactFileLimit, PNG_A.length), clock);
        ImageShareService service = service(database, storage, clock, images, blobs,
                new ImageShareService.SecurityDependencies(null, quota));

        ImageShare first = upload(service, PNG_A, owner("quota-a"));
        ImageShare reused = upload(service, PNG_A.clone(), owner("quota-a"));
        ImageShare otherOwner = upload(service, PNG_A.clone(), owner("quota-b"));

        assertEquals(first.code(), reused.code());
        assertEquals(first.blobId(), otherOwner.blobId());
        assertEquals(PNG_A.length, security.globalManagedStorageBytes());
        assertEquals(PNG_A.length, security.activeStorageBytes("quota-quota-a", clock.millis()));
        assertEquals(PNG_A.length, security.activeStorageBytes("quota-quota-b", clock.millis()));
        assertEquals(1L, security.countCreatedShares("quota-quota-a", 0L));
        assertEquals(1L, security.countCreatedShares("quota-quota-b", 0L));

        MediaQuotaService.TierLimits tooSmallForLogicalShare = new MediaQuotaService.TierLimits(
                100, 100, PNG_A.length - 1L, 24L * 60L * 60L * 1000L);
        MediaQuotaService restrictedQuota = new MediaQuotaService(security,
                new MediaQuotaService.Options(true, tooSmallForLogicalShare,
                        tooSmallForLogicalShare, PNG_A.length), clock);
        ImageShareService restrictedService = service(database, storage, clock, images, blobs,
                new ImageShareService.SecurityDependencies(null, restrictedQuota));
        ImageShareService.UploadResult rejected = restrictedService.create(
                new ImageShareService.Upload(PNG_A.clone(), false, "", 60L * 60L * 1000L),
                owner("quota-c"));

        assertEquals(ImageShareService.UploadError.ACTIVE_STORAGE_QUOTA_EXCEEDED, rejected.error());
        assertEquals(2L, count(database, "short_url_images"));
        assertEquals(1L, count(database, "media_blobs"));
    }

    @Test
    void sqliteSchemaMigratesLegacyTableAndEnforcesBlobConstraintsAndIndexes() throws Exception {
        Path database = tempDir.resolve("schema.db");
        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE short_url_images (
                        code TEXT PRIMARY KEY,
                        storage_name TEXT NOT NULL,
                        content_type TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        password_hash TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO short_url_images
                        (code, storage_name, content_type, size_bytes, created_at, expires_at, password_hash)
                    VALUES ('legacy', 'legacy.png', 'image/png', 9, 1, 9999999999999, '')
                    """);
        }

        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaBlobRepository blobs = new SqliteMediaBlobRepository(database);
        MediaBlob first = blobs.saveIfAbsent(new MediaBlob(
                0L, "a".repeat(64), "blob.png", PNG_A.length, "image/png", ".png", 1L,
                MediaStorageState.ACTIVE, "", 0L));
        MediaBlob duplicateHash = blobs.saveIfAbsent(new MediaBlob(
                0L, "a".repeat(64), "other.png", PNG_A.length, "image/png", ".png", 2L,
                MediaStorageState.ACTIVE, "", 0L));

        assertEquals(first.id(), duplicateHash.id());
        assertEquals(1L, count(database, "media_blobs"));
        assertEquals(1L, count(database, "short_url_images"));
        assertTrue(sqliteQueryHasRow(database, """
                SELECT 1 FROM pragma_foreign_key_list('short_url_images')
                WHERE "table" = 'media_blobs' AND "from" = 'blob_id' AND "to" = 'id'
                """));
        assertTrue(sqliteQueryHasRow(database, """
                SELECT 1 FROM pragma_index_list('media_blobs') AS indexes
                JOIN pragma_index_info(indexes.name) AS columns
                WHERE indexes."unique" = 1 AND columns.name = 'sha256'
                """));
        assertTrue(sqliteQueryHasRow(database, """
                SELECT 1 FROM pragma_index_list('short_url_images')
                WHERE name = 'idx_short_url_images_blob'
                """));
        assertTrue(orphanQueryUsesBlobIndex(database));

        ImageShare invalidReference = new ImageShare(
                "invalidBlob", "missing.png", "image/png", PNG_A.length, 1L, 9_999_999_999_999L,
                "", "b".repeat(64), 0L, MediaStorageState.ACTIVE, "", 0L,
                MediaOwnerType.DISCORD_USER, "owner", "quota-owner", "device", "ip", 0L, 999L);
        assertThrows(IllegalStateException.class, () -> images.save(invalidReference));
    }

    private ImageShare concurrentUpload(ImageShareService service, QuotaSubject owner,
                                        CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return upload(service, PNG_A.clone(), owner);
    }

    private ImageShare upload(ImageShareService service, byte[] content, QuotaSubject owner) {
        ImageShareService.UploadResult result = service.create(
                new ImageShareService.Upload(content, false, "", 60L * 60L * 1000L), owner);
        assertTrue(result.isSuccess(), () -> "upload failed: " + result.error());
        return result.imageShare();
    }

    private Fixture fixture(String databaseName) {
        Path database = tempDir.resolve(databaseName);
        Path active = tempDir.resolve(databaseName + "-active");
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                active, tempDir.resolve(databaseName + "-tmp"), tempDir.resolve(databaseName + "-archive"));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-02T08:00:00Z"));
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        return new Fixture(database, active, storage, clock, images,
                service(database, storage, clock, images));
    }

    private ImageShareService service(Path database, FileSystemImageShareStorage storage,
                                      Clock clock) {
        return service(database, storage, clock, new SqliteImageShareRepository(database));
    }

    private ImageShareService service(Path database, FileSystemImageShareStorage storage,
                                      Clock clock, SqliteImageShareRepository images) {
        return service(database, storage, clock, images, new SqliteMediaBlobRepository(database), null);
    }

    private ImageShareService service(Path database, FileSystemImageShareStorage storage,
                                      Clock clock, SqliteImageShareRepository images,
                                      SqliteMediaBlobRepository blobs,
                                      ImageShareService.SecurityDependencies securityDependencies) {
        return new ImageShareService(
                images,
                blobs,
                new SqliteShortUrlRepository(database),
                storage,
                new ImageShareService.Options(
                        true, 60L * 60L * 1000L, 24L * 60L * 60L * 1000L,
                        20L * 1024L * 1024L, 100L * 1024L * 1024L,
                        5L * 60L * 1000L, 30L * 24L * 60L * 60L * 1000L,
                        60_000L, 7),
                clock,
                securityDependencies);
    }

    private QuotaSubject owner(String ownerId) {
        return new QuotaSubject(AccessTier.AUTHENTICATED, "quota-" + ownerId,
                MediaOwnerType.DISCORD_USER, ownerId, "device-" + ownerId, "ip-" + ownerId);
    }

    private ImageShare legacyShare(String code, String storageName, String ownerId,
                                   long views, long now, long expiresAt) {
        return new ImageShare(code, storageName, "image/png", PNG_A.length,
                now, expiresAt, "", "", views,
                com.norule.musicbot.domain.shorturl.MediaStorageState.ACTIVE,
                "", 0L, MediaOwnerType.DISCORD_USER, ownerId,
                "quota-" + ownerId, "device-" + ownerId, "ip-" + ownerId, now + 1L);
    }

    private long count(Path database, String table) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return row.next() ? row.getLong(1) : 0L;
        }
    }

    private long regularFileCount(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private boolean sqliteQueryHasRow(Path database, String sql) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next();
        }
    }

    private boolean orphanQueryUsesBlobIndex(Path database) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     EXPLAIN QUERY PLAN
                     SELECT b.id FROM media_blobs b
                     WHERE NOT EXISTS (
                         SELECT 1 FROM short_url_images s WHERE s.blob_id = b.id
                     )
                     """)) {
            while (rows.next()) {
                if (rows.getString("detail").contains("idx_short_url_images_blob")) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Fixture(Path database, Path activeDirectory,
                           FileSystemImageShareStorage storage, MutableClock clock,
                           SqliteImageShareRepository images, ImageShareService service) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }
    }
}
