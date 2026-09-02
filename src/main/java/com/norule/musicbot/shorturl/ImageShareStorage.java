package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public interface ImageShareStorage {
    enum ArchiveStatus {
        ARCHIVED,
        ALREADY_ARCHIVED,
        LEGACY_MIGRATED,
        MISSING
    }

    record ArchiveResult(ArchiveStatus status, String archiveStorageName) {
        public ArchiveResult {
            if (status == null) {
                throw new IllegalArgumentException("archive status cannot be null");
            }
            archiveStorageName = archiveStorageName == null ? "" : archiveStorageName;
        }
    }

    void save(ImageShare imageShare, byte[] content) throws IOException;

    InputStream open(ImageShare imageShare) throws IOException;

    boolean exists(ImageShare imageShare);

    void delete(ImageShare imageShare) throws IOException;

    default String archive(ImageShare imageShare) throws IOException {
        throw new IOException("Archive storage is not configured");
    }

    /**
     * Archives a media file or reconciles a previous archive attempt. Implementations that can
     * distinguish a missing file from an I/O failure should override this method.
     */
    default ArchiveResult archiveOrReconcile(ImageShare imageShare) throws IOException {
        return new ArchiveResult(ArchiveStatus.ARCHIVED, archive(imageShare));
    }

    default boolean existsArchived(ImageShare imageShare) {
        return false;
    }

    default InputStream openArchived(ImageShare imageShare) throws IOException {
        throw new IOException("Archive storage is not configured");
    }

    default InputStream openLegacy(ImageShare imageShare) throws IOException {
        return null;
    }

    default void deleteLegacy(ImageShare imageShare) throws IOException {
        // Nothing to delete for storage implementations without legacy locations.
    }

    default void deleteArchived(ImageShare imageShare) throws IOException {
        // Nothing to delete for storage implementations without archive support.
    }

    default int filesystemUsagePercent() {
        return 0;
    }

    default Path createTemporaryUpload() throws IOException {
        return Files.createTempFile("norule-media-upload-", ".tmp");
    }

    default void deleteTemporaryUpload(Path path) throws IOException {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }
}
