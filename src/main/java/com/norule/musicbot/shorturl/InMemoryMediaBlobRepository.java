package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.MediaBlob;
import com.norule.musicbot.domain.shorturl.MediaStorageState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryMediaBlobRepository implements MediaBlobRepository {
    private final ConcurrentHashMap<Long, MediaBlob> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> idByHash = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1L);

    @Override
    public MediaBlob findBySha256(String sha256) {
        Long id = idByHash.get(sha256);
        return id == null ? null : byId.get(id);
    }

    @Override
    public MediaBlob findById(long blobId) {
        return byId.get(blobId);
    }

    @Override
    public MediaBlob saveIfAbsent(MediaBlob blob) {
        Long id = idByHash.computeIfAbsent(blob.sha256(), ignored -> nextId.getAndIncrement());
        byId.computeIfAbsent(id, ignored -> blob.withId(id));
        return byId.get(id);
    }

    @Override
    public void update(MediaBlob blob) {
        byId.put(blob.id(), blob);
        idByHash.put(blob.sha256(), blob.id());
    }

    @Override
    public void deleteById(long blobId) {
        MediaBlob removed = byId.remove(blobId);
        if (removed != null) {
            idByHash.remove(removed.sha256(), blobId);
        }
    }

    @Override
    public List<MediaBlob> findByStorageStates(Set<MediaStorageState> states) {
        return byId.values().stream().filter(blob -> states.contains(blob.storageState())).toList();
    }

    @Override
    public List<MediaBlob> findOrphans(long createdBeforeMillis) {
        return List.of();
    }
}
