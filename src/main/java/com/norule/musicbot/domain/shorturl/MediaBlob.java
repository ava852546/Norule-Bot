package com.norule.musicbot.domain.shorturl;

/**
 * Physical media content shared by one or more {@link ImageShare} records.
 */
public record MediaBlob(
        long id,
        String sha256,
        String storageName,
        long sizeBytes,
        String contentType,
        String extension,
        long createdAt,
        MediaStorageState storageState,
        String archiveStorageName,
        long archivedAt
) {
    public MediaBlob {
        sha256 = sha256 == null ? "" : sha256;
        storageName = storageName == null ? "" : storageName;
        sizeBytes = Math.max(0L, sizeBytes);
        contentType = contentType == null ? "application/octet-stream" : contentType;
        extension = extension == null ? "" : extension;
        storageState = storageState == null ? MediaStorageState.ACTIVE : storageState;
        archiveStorageName = archiveStorageName == null ? "" : archiveStorageName;
    }

    public MediaBlob withId(long persistedId) {
        return new MediaBlob(persistedId, sha256, storageName, sizeBytes, contentType, extension,
                createdAt, storageState, archiveStorageName, archivedAt);
    }

    public MediaBlob withStorageState(MediaStorageState updatedState,
                                      String updatedArchiveStorageName,
                                      long updatedArchivedAt) {
        return new MediaBlob(id, sha256, storageName, sizeBytes, contentType, extension, createdAt,
                updatedState, updatedArchiveStorageName, updatedArchivedAt);
    }
}
