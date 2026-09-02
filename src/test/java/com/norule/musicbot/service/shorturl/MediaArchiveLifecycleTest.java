package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaStorageState;
import com.norule.musicbot.service.shorturl.AnonymousDeviceIdentityService.DeviceIdentity;
import com.norule.musicbot.shorturl.ImageShareStorage;
import com.norule.musicbot.shorturl.SqliteImageShareRepository;
import com.norule.musicbot.shorturl.SqliteMediaBlobRepository;
import com.norule.musicbot.shorturl.SqliteMediaSecurityRepository;
import com.norule.musicbot.shorturl.SqliteShortUrlRepository;
import com.norule.musicbot.shorturl.infra.FileSystemImageShareStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaArchiveLifecycleTest {
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @TempDir
    Path tempDir;

    @Test
    void expiredMediaArchivesReleasesUserQuotaButKeepsGlobalBytesUntilManualDeletion() throws Exception {
        Path database = tempDir.resolve("archive.db");
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        Path active = tempDir.resolve("active");
        Path archive = tempDir.resolve("expired");
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                active, tempDir.resolve("tmp"), archive);
        ImageShareService service = service(database, images, security, storage, clock);
        AnonymousDeviceIdentityService identities = new AnonymousDeviceIdentityService(
                security, AnonymousDeviceIdentityService.Options.defaults(),
                "quota-secret", "device-secret", clock);
        DeviceIdentity device = identities.resolveAnonymous("", "203.0.113.5");

        ImageShare created = service.create(new ImageShareService.Upload(
                PNG, false, "", 60_000L), device.quotaSubject()).imageShare();
        assertNotNull(created);
        assertEquals(PNG.length, security.activeStorageBytes(device.quotaSubject().quotaGroupId()));

        clock.advanceMillis(60_000L);
        assertNull(service.resolve(created.code()));
        ImageShare archived = images.findByCode(created.code());
        assertEquals(MediaStorageState.ARCHIVED, archived.storageState());
        assertFalse(Files.exists(active.resolve(created.storageName())));
        assertTrue(Files.exists(archive.resolve(created.storageName())));
        assertNull(service.open(archived));
        assertEquals(0L, security.activeStorageBytes(device.quotaSubject().quotaGroupId()));
        assertEquals(PNG.length, security.globalManagedStorageBytes());

        Files.delete(archive.resolve(created.storageName()));
        service.cleanupExpired();
        assertEquals(MediaStorageState.ARCHIVE_DELETED,
                images.findByCode(created.code()).storageState());
        assertEquals(0L, security.globalManagedStorageBytes());
    }

    @Test
    void archiveFailureStaysPrivateAndBackgroundRetryEventuallyArchives() throws Exception {
        Path database = tempDir.resolve("retry.db");
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        Path active = tempDir.resolve("retry-active");
        Path archive = tempDir.resolve("blocked-archive");
        Files.writeString(archive, "not a directory");
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                active, tempDir.resolve("retry-tmp"), archive);
        ImageShareService service = service(database, images, security, storage, clock);
        ImageShare created = service.create(new ImageShareService.Upload(PNG, false, "", 60_000L)).imageShare();

        clock.advanceMillis(60_000L);
        assertNull(service.resolve(created.code()));
        assertEquals(MediaStorageState.ARCHIVE_PENDING,
                images.findByCode(created.code()).storageState());
        assertTrue(Files.exists(active.resolve(created.storageName())));
        assertNull(service.open(images.findByCode(created.code())));

        Files.delete(archive);
        Files.createDirectories(archive);
        service.cleanupExpired();
        assertEquals(MediaStorageState.ARCHIVED,
                images.findByCode(created.code()).storageState());
        assertTrue(Files.exists(archive.resolve(created.storageName())));
    }

    @Test
    void activeMissingWithExistingArchiveReconcilesMetadata() throws Exception {
        Path database = tempDir.resolve("existing-archive.db");
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        Path active = tempDir.resolve("existing-active");
        Path archive = tempDir.resolve("existing-archive");
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                active, tempDir.resolve("existing-tmp"), archive);
        ImageShareService service = service(database, images, security, storage, clock);
        ImageShare expired = expiredShare("already1", clock.millis());
        images.save(expired);
        Files.createDirectories(archive);
        Files.write(archive.resolve(expired.storageName()), PNG);

        service.cleanupExpired();

        ImageShare reconciled = images.findByCode(expired.code());
        assertEquals(MediaStorageState.ARCHIVED, reconciled.storageState());
        assertEquals(expired.storageName(), reconciled.archiveStorageName());
        assertTrue(Files.exists(archive.resolve(expired.storageName())));
        service.cleanupExpired();
        assertEquals(MediaStorageState.ARCHIVED, images.findByCode(expired.code()).storageState());
    }

    @Test
    void activeMissingWithLegacyFileMigratesIntoArchive() throws Exception {
        Path database = tempDir.resolve("legacy-file.db");
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        Path active = tempDir.resolve("new-active");
        Path archive = tempDir.resolve("legacy-archive");
        Path legacy = tempDir.resolve("old-active");
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                active, tempDir.resolve("legacy-tmp"), archive, List.of(legacy));
        ImageShareService service = service(database, images, security, storage, clock);
        ImageShare expired = expiredShare("legacy1", clock.millis());
        images.save(expired);
        Files.createDirectories(legacy);
        Files.write(legacy.resolve(expired.storageName()), PNG);

        service.cleanupExpired();

        assertEquals(MediaStorageState.ARCHIVED, images.findByCode(expired.code()).storageState());
        assertFalse(Files.exists(legacy.resolve(expired.storageName())));
        assertTrue(Files.exists(archive.resolve(expired.storageName())));
    }

    @Test
    void missingEverywhereBecomesTerminalAndRepeatedCleanupDoesNotRetry() {
        Path database = tempDir.resolve("missing.db");
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        CountingArchiveStorage storage = new CountingArchiveStorage(new FileSystemImageShareStorage(
                tempDir.resolve("missing-active"), tempDir.resolve("missing-tmp"),
                tempDir.resolve("missing-archive"), List.of(tempDir.resolve("missing-legacy"))));
        ImageShareService service = service(database, images, security, storage, clock);
        ImageShare expired = expiredShare("missing1", clock.millis());
        images.save(expired);

        service.cleanupExpired();
        assertEquals(MediaStorageState.MISSING, images.findByCode(expired.code()).storageState());
        assertEquals(1, storage.archiveAttempts);

        service.cleanupExpired();
        assertEquals(MediaStorageState.MISSING, images.findByCode(expired.code()).storageState());
        assertEquals(1, storage.archiveAttempts);
    }

    @Test
    void permissionDeniedRemainsPendingAndRetriesOnLaterCleanup() {
        Path database = tempDir.resolve("permission.db");
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        FailingArchiveStorage storage = new FailingArchiveStorage(
                new AccessDeniedException("archive-path"));
        ImageShareService service = service(database, images, security, storage, clock);
        ImageShare expired = expiredShare("denied1", clock.millis());
        images.save(expired);

        service.cleanupExpired();
        assertEquals(MediaStorageState.ARCHIVE_PENDING,
                images.findByCode(expired.code()).storageState());
        assertEquals(1, storage.archiveAttempts);

        service.cleanupExpired();
        assertEquals(MediaStorageState.ARCHIVE_PENDING,
                images.findByCode(expired.code()).storageState());
        assertEquals(2, storage.archiveAttempts);
    }

    @Test
    void legacyMetadataWithoutLifecycleColumnsIsMigratedAndReconciled() throws Exception {
        Path database = tempDir.resolve("legacy-metadata.db");
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        ImageShare expired = expiredShare("legacy2", clock.millis());
        createLegacyImageMetadata(database, expired);
        SqliteImageShareRepository images = new SqliteImageShareRepository(database);
        SqliteMediaSecurityRepository security = new SqliteMediaSecurityRepository(database);
        Path active = tempDir.resolve("metadata-active");
        Path archive = tempDir.resolve("metadata-archive");
        Path legacy = tempDir.resolve("metadata-legacy");
        Files.createDirectories(legacy);
        Files.write(legacy.resolve(expired.storageName()), PNG);
        FileSystemImageShareStorage storage = new FileSystemImageShareStorage(
                active, tempDir.resolve("metadata-tmp"), archive, List.of(legacy));
        ImageShareService service = service(database, images, security, storage, clock);

        assertEquals(MediaStorageState.ACTIVE, images.findByCode(expired.code()).storageState());
        service.cleanupExpired();

        ImageShare migrated = images.findByCode(expired.code());
        assertEquals(MediaStorageState.ARCHIVED, migrated.storageState());
        assertTrue(Files.exists(archive.resolve(expired.storageName())));
    }

    private ImageShareService service(Path database, SqliteImageShareRepository images,
                                      SqliteMediaSecurityRepository security,
                                      ImageShareStorage storage, Clock clock) {
        MediaPasswordAttemptGuard passwordGuard = new MediaPasswordAttemptGuard(
                security, MediaPasswordAttemptGuard.Options.defaults(), "password-secret", clock);
        MediaQuotaService.Options defaults = MediaQuotaService.Options.defaults();
        MediaQuotaService quota = new MediaQuotaService(security,
                new MediaQuotaService.Options(true, defaults.anonymous(), defaults.authenticated(),
                        1024L * 1024L), clock);
        return new ImageShareService(images, new SqliteMediaBlobRepository(database),
                new SqliteShortUrlRepository(database), storage,
                new ImageShareService.Options(true, 60_000L, 24L * 60L * 60L * 1000L,
                        20L * 1024L * 1024L, 100L * 1024L * 1024L, 5L * 60L * 1000L,
                        30L * 24L * 60L * 60L * 1000L, 60_000L, 7),
                clock, new ImageShareService.SecurityDependencies(passwordGuard, quota));
    }

    private ImageShare expiredShare(String code, long now) {
        return new ImageShare(code, code + ".png", "image/png", PNG.length,
                now - 60_000L, now - 1L, "", "");
    }

    private void createLegacyImageMetadata(Path database, ImageShare imageShare) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
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
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO short_url_images
                        (code, storage_name, content_type, size_bytes, created_at, expires_at, password_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, imageShare.code());
                insert.setString(2, imageShare.storageName());
                insert.setString(3, imageShare.contentType());
                insert.setLong(4, imageShare.sizeBytes());
                insert.setLong(5, imageShare.createdAt());
                insert.setLong(6, imageShare.expiresAt());
                insert.setString(7, imageShare.passwordHash());
                insert.executeUpdate();
            }
        }
    }

    private static final class FailingArchiveStorage implements ImageShareStorage {
        private final IOException failure;
        private int archiveAttempts;

        private FailingArchiveStorage(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void save(ImageShare imageShare, byte[] content) {
            // Not needed by archive lifecycle tests.
        }

        @Override
        public InputStream open(ImageShare imageShare) {
            return InputStream.nullInputStream();
        }

        @Override
        public boolean exists(ImageShare imageShare) {
            return false;
        }

        @Override
        public void delete(ImageShare imageShare) {
            // Not needed by archive lifecycle tests.
        }

        @Override
        public ArchiveResult archiveOrReconcile(ImageShare imageShare) throws IOException {
            archiveAttempts++;
            throw failure;
        }
    }

    private static final class CountingArchiveStorage implements ImageShareStorage {
        private final ImageShareStorage delegate;
        private int archiveAttempts;

        private CountingArchiveStorage(ImageShareStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(ImageShare imageShare, byte[] content) throws IOException {
            delegate.save(imageShare, content);
        }

        @Override
        public InputStream open(ImageShare imageShare) throws IOException {
            return delegate.open(imageShare);
        }

        @Override
        public boolean exists(ImageShare imageShare) {
            return delegate.exists(imageShare);
        }

        @Override
        public void delete(ImageShare imageShare) throws IOException {
            delegate.delete(imageShare);
        }

        @Override
        public ArchiveResult archiveOrReconcile(ImageShare imageShare) throws IOException {
            archiveAttempts++;
            return delegate.archiveOrReconcile(imageShare);
        }
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
