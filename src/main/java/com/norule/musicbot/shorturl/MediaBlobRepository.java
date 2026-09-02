package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.MediaBlob;
import com.norule.musicbot.domain.shorturl.MediaStorageState;

import java.util.List;
import java.util.Set;

public interface MediaBlobRepository {
    MediaBlob findBySha256(String sha256);

    MediaBlob findById(long blobId);

    /**
     * Inserts a blob or returns the row that won a concurrent SHA-256 unique-key race.
     */
    MediaBlob saveIfAbsent(MediaBlob blob);

    void update(MediaBlob blob);

    void deleteById(long blobId);

    List<MediaBlob> findByStorageStates(Set<MediaStorageState> states);

    /**
     * Returns unreferenced blobs old enough that an in-flight share creation cannot reasonably
     * still attach to them.
     */
    List<MediaBlob> findOrphans(long createdBeforeMillis);
}
