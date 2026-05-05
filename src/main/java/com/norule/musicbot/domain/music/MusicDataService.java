package com.norule.musicbot.domain.music;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongToIntFunction;

public class MusicDataService {
    public record PlaybackEntry(
            long playedAtEpochMillis,
            String title,
            String author,
            String source,
            String uri,
            String artworkUrl,
            long durationMillis,
            Long requesterId,
            String requesterName
    ) {
        public String label() {
            String safeTitle = title == null || title.isBlank() ? "-" : title.trim();
            String safeAuthor = author == null || author.isBlank() ? "-" : author.trim();
            return safeTitle + " - " + safeAuthor;
        }

        public String songKey() {
            String safeTitle = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
            String safeAuthor = author == null ? "" : author.trim().toLowerCase(Locale.ROOT);
            String safeUri = uri == null ? "" : uri.trim().toLowerCase(Locale.ROOT);
            if (!safeTitle.isBlank() || !safeAuthor.isBlank()) {
                return safeTitle + "||" + safeAuthor;
            }
            return safeUri;
        }
    }

    public record MusicStatsSnapshot(
            String topSongLabel,
            int topSongCount,
            Long topRequesterId,
            int topRequesterCount,
            long todayPlaybackMillis,
            int historyCount
    ) {
    }

    public record PlaylistSummary(
            String name,
            int trackCount,
            long updatedAtEpochMillis,
            Long ownerId,
            String ownerName
    ) {
    }

    public enum PlaylistMutationStatus {
        SUCCESS,
        EMPTY,
        NOT_FOUND,
        NAME_CONFLICT,
        NOT_OWNER,
        INVALID_CODE,
        INVALID_INDEX,
        DUPLICATE,
        LIMIT_REACHED
    }

    public record PlaylistSaveResult(
            PlaylistMutationStatus status,
            String playlistName,
            int trackCount,
            Long ownerId,
            String ownerName
    ) {
    }

    public record PlaylistDeleteResult(
            PlaylistMutationStatus status,
            String playlistName,
            Long ownerId,
            String ownerName
    ) {
    }

    public record PlaylistTrackRemoveResult(
            PlaylistMutationStatus status,
            String playlistName,
            int removedIndex,
            String removedTitle,
            int trackCount,
            Long ownerId,
            String ownerName
    ) {
    }

    public record PlaylistTrackAddResult(
            PlaylistMutationStatus status,
            String playlistName,
            String addedTitle,
            int trackCount,
            Long ownerId,
            String ownerName
    ) {
    }

    public record PlaylistShareCode(
            String code,
            String playlistName,
            int trackCount,
            long expiresAtEpochMillis
    ) {
    }

    public record PlaylistImportResult(
            PlaylistMutationStatus status,
            String playlistName,
            int trackCount,
            Long ownerId,
            String ownerName
    ) {
    }

    private static class PlaylistData {
        String name = "";
        long updatedAtEpochMillis = 0L;
        Long ownerId = null;
        String ownerName = "";
        List<PlaybackEntry> tracks = new ArrayList<>();
    }

    private static class SharedPlaylistData {
        String code = "";
        String playlistName = "";
        long expiresAtEpochMillis = 0L;
        List<PlaybackEntry> tracks = new ArrayList<>();
    }

    private static class GuildMusicData {
        int volume = 100;
        double playbackSpeed = 1.0d;
        LinkedList<PlaybackEntry> history = new LinkedList<>();
        Map<String, Integer> songPlayCounts = new LinkedHashMap<>();
        Map<String, String> songLabels = new LinkedHashMap<>();
        Map<String, Long> songLastPlayedAt = new LinkedHashMap<>();
        Map<Long, Integer> requesterCounts = new LinkedHashMap<>();
        Map<Long, Long> requesterLastPlayedAt = new LinkedHashMap<>();
        String todayDate = LocalDate.now().toString();
        long todayPlaybackMillis = 0L;
        Map<String, PlaylistData> playlists = new LinkedHashMap<>();
    }

    private static final long PLAYLIST_SHARE_TTL_MILLIS = 180_000L;

    private final Path dir;
    private volatile LongToIntFunction historyLimitProvider;
    private volatile LongToIntFunction statsRetentionDaysProvider;
    private volatile LongToIntFunction playlistTrackLimitProvider;
    private final Map<Long, GuildMusicData> cache = new ConcurrentHashMap<>();
    private final Map<String, SharedPlaylistData> playlistShares = new ConcurrentHashMap<>();
    private final SqliteMusicStore sqliteStore;

    public MusicDataService(Path dir,
                            LongToIntFunction historyLimitProvider,
                            LongToIntFunction statsRetentionDaysProvider,
                            LongToIntFunction playlistTrackLimitProvider) {
        this(dir, historyLimitProvider, statsRetentionDaysProvider, playlistTrackLimitProvider, null);
    }

    public MusicDataService(Path dir,
                            LongToIntFunction historyLimitProvider,
                            LongToIntFunction statsRetentionDaysProvider,
                            LongToIntFunction playlistTrackLimitProvider,
                            Path sqliteDbPath) {
        this.dir = dir;
        this.historyLimitProvider = historyLimitProvider == null ? ignored -> 100 : historyLimitProvider;
        this.statsRetentionDaysProvider = statsRetentionDaysProvider == null ? ignored -> 30 : statsRetentionDaysProvider;
        this.playlistTrackLimitProvider = playlistTrackLimitProvider == null ? ignored -> 100 : playlistTrackLimitProvider;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create music data directory: " + dir.toAbsolutePath(), e);
        }
        this.sqliteStore = createSqliteStore(sqliteDbPath);
    }

    private SqliteMusicStore createSqliteStore(Path sqliteDbPath) {
        if (sqliteDbPath == null) {
            return null;
        }
        try {
            SqliteMusicStore store = new SqliteMusicStore(sqliteDbPath);
            System.out.println("[NoRule] Music history/playlist storage: sqlite (" + sqliteDbPath.toAbsolutePath().normalize() + ")");
            return store;
        } catch (Exception e) {
            System.out.println("[NoRule] Music sqlite storage disabled: " + e.getMessage());
            return null;
        }
    }

    public void replaceGuildLimits(LongToIntFunction historyLimitProvider,
                                   LongToIntFunction statsRetentionDaysProvider,
                                   LongToIntFunction playlistTrackLimitProvider) {
        this.historyLimitProvider = historyLimitProvider == null ? ignored -> 100 : historyLimitProvider;
        this.statsRetentionDaysProvider = statsRetentionDaysProvider == null ? ignored -> 30 : statsRetentionDaysProvider;
        this.playlistTrackLimitProvider = playlistTrackLimitProvider == null ? ignored -> 100 : playlistTrackLimitProvider;
    }

    public void reloadAll() {
        cache.clear();
        playlistShares.clear();
    }

    public void reload(long guildId) {
        cache.remove(guildId);
    }

    public void cleanupTransientCaches() {
        cleanupExpiredPlaylistShares();
    }

    public int getVolume(long guildId) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            return clampVolume(data.volume);
        }
    }

    public int setVolume(long guildId, int volume) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            data.volume = clampVolume(volume);
            save(guildId, data);
            return data.volume;
        }
    }

    public double getPlaybackSpeed(long guildId) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            return clampPlaybackSpeed(data.playbackSpeed);
        }
    }

    public double setPlaybackSpeed(long guildId, double speed) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            data.playbackSpeed = clampPlaybackSpeed(speed);
            save(guildId, data);
            return data.playbackSpeed;
        }
    }

    public void resetPlaybackSpeed(long guildId) {
        setPlaybackSpeed(guildId, 1.0d);
    }

    public void recordTrackStarted(long guildId, PlaybackEntry entry) {
        if (entry == null) {
            return;
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            rolloverDailyStats(data);
            data.history.addFirst(entry);
            trimHistory(guildId, data);

            String songKey = entry.songKey();
            long now = Instant.now().toEpochMilli();
            if (!songKey.isBlank()) {
                data.songPlayCounts.merge(songKey, 1, Integer::sum);
                data.songLabels.put(songKey, entry.label());
                data.songLastPlayedAt.put(songKey, now);
            }
            if (entry.requesterId() != null) {
                data.requesterCounts.merge(entry.requesterId(), 1, Integer::sum);
                data.requesterLastPlayedAt.put(entry.requesterId(), now);
            }
            pruneExpiredStats(guildId, data, now);
            save(guildId, data);
        }
    }

    public void recordTrackFinished(long guildId, long playedMillis) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            rolloverDailyStats(data);
            data.todayPlaybackMillis += Math.max(0L, playedMillis);
            save(guildId, data);
        }
    }

    public List<PlaybackEntry> getRecentHistory(long guildId, int limit) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            int max = Math.max(1, Math.min(limit, data.history.size()));
            return new ArrayList<>(data.history.subList(0, max));
        }
    }

    public MusicStatsSnapshot getStats(long guildId) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            rolloverDailyStats(data);
            pruneExpiredStats(guildId, data, Instant.now().toEpochMilli());

            String topSongKey = null;
            int topSongCount = 0;
            for (Map.Entry<String, Integer> entry : data.songPlayCounts.entrySet()) {
                int count = entry.getValue() == null ? 0 : entry.getValue();
                if (count > topSongCount) {
                    topSongKey = entry.getKey();
                    topSongCount = count;
                }
            }

            Long topRequesterId = null;
            int topRequesterCount = 0;
            for (Map.Entry<Long, Integer> entry : data.requesterCounts.entrySet()) {
                int count = entry.getValue() == null ? 0 : entry.getValue();
                if (count > topRequesterCount) {
                    topRequesterId = entry.getKey();
                    topRequesterCount = count;
                }
            }

            String topSongLabel = topSongKey == null ? null : data.songLabels.getOrDefault(topSongKey, topSongKey);
            return new MusicStatsSnapshot(
                    topSongLabel,
                    topSongCount,
                    topRequesterId,
                    topRequesterCount,
                    data.todayPlaybackMillis,
                    data.history.size()
            );
        }
    }

    public boolean wasRecentlyPlayed(long guildId, PlaybackEntry entry, int limit) {
        if (entry == null) {
            return false;
        }
        String key = entry.songKey();
        if (key.isBlank()) {
            return false;
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            int max = Math.max(1, Math.min(limit, data.history.size()));
            for (int i = 0; i < max; i++) {
                PlaybackEntry historyEntry = data.history.get(i);
                if (key.equals(historyEntry.songKey())) {
                    return true;
                }
            }
            return false;
        }
    }

    public PlaylistSaveResult savePlaylist(long guildId, String name, List<PlaybackEntry> tracks, Long requesterId, String requesterName) {
        String normalized = normalizePlaylistName(name);
        if (normalized.isBlank() || tracks == null || tracks.isEmpty()) {
            return new PlaylistSaveResult(PlaylistMutationStatus.EMPTY, safePlaylistDisplayName(name), 0, null, "");
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            PlaylistData existing = data.playlists.get(normalized);
            if (existing != null) {
                if (existing.ownerId != null && !existing.ownerId.equals(requesterId)) {
                    return new PlaylistSaveResult(
                            PlaylistMutationStatus.NAME_CONFLICT,
                            existing.name,
                            existing.tracks == null ? 0 : existing.tracks.size(),
                            existing.ownerId,
                            safePlaylistOwnerName(existing.ownerName)
                    );
                }
                if (existing.tracks == null) {
                    existing.tracks = new ArrayList<>();
                }
                int existingSize = existing.tracks.size();
                int limit = getPlaylistTrackLimit(guildId);
                for (PlaybackEntry track : tracks) {
                    if (track == null || existing.tracks.size() >= limit || containsPlaylistTrack(existing.tracks, track)) {
                        continue;
                    }
                    existing.tracks.add(track);
                }
                existing.updatedAtEpochMillis = Instant.now().toEpochMilli();
                if (existing.ownerId == null) {
                    existing.ownerId = requesterId;
                }
                if (existing.ownerName == null || existing.ownerName.isBlank()) {
                    existing.ownerName = safePlaylistOwnerName(requesterName);
                }
                if (existing.tracks.size() == existingSize) {
                    return new PlaylistSaveResult(
                            PlaylistMutationStatus.DUPLICATE,
                            existing.name,
                            existing.tracks.size(),
                            existing.ownerId,
                            safePlaylistOwnerName(existing.ownerName)
                    );
                }
                save(guildId, data);
                return new PlaylistSaveResult(
                        PlaylistMutationStatus.SUCCESS,
                        existing.name,
                        existing.tracks.size(),
                        existing.ownerId,
                        safePlaylistOwnerName(existing.ownerName)
                );
            }
            PlaylistData playlist = new PlaylistData();
            playlist.name = safePlaylistDisplayName(name);
            playlist.updatedAtEpochMillis = Instant.now().toEpochMilli();
            playlist.ownerId = requesterId;
            playlist.ownerName = safePlaylistOwnerName(requesterName);
            for (PlaybackEntry track : tracks) {
                if (track == null || containsPlaylistTrack(playlist.tracks, track)) {
                    continue;
                }
                playlist.tracks.add(track);
                if (playlist.tracks.size() >= getPlaylistTrackLimit(guildId)) {
                    break;
                }
            }
            if (playlist.tracks.isEmpty()) {
                return new PlaylistSaveResult(PlaylistMutationStatus.EMPTY, playlist.name, 0, requesterId, playlist.ownerName);
            }
            data.playlists.put(normalized, playlist);
            save(guildId, data);
            return new PlaylistSaveResult(
                    PlaylistMutationStatus.SUCCESS,
                    playlist.name,
                    playlist.tracks.size(),
                    playlist.ownerId,
                    playlist.ownerName
            );
        }
    }

    public PlaylistDeleteResult deletePlaylist(long guildId, String name, Long requesterId) {
        return deletePlaylist(guildId, name, requesterId, false);
    }

    public PlaylistDeleteResult deletePlaylist(long guildId, String name, Long requesterId, boolean allowManageOverride) {
        String normalized = normalizePlaylistName(name);
        if (normalized.isBlank()) {
            return new PlaylistDeleteResult(PlaylistMutationStatus.NOT_FOUND, safePlaylistDisplayName(name), null, "");
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            PlaylistData existing = data.playlists.get(normalized);
            if (existing == null) {
                return new PlaylistDeleteResult(PlaylistMutationStatus.NOT_FOUND, safePlaylistDisplayName(name), null, "");
            }
            if (!allowManageOverride && existing.ownerId != null && !existing.ownerId.equals(requesterId)) {
                return new PlaylistDeleteResult(
                        PlaylistMutationStatus.NOT_OWNER,
                        existing.name,
                        existing.ownerId,
                        safePlaylistOwnerName(existing.ownerName)
                );
            }
            data.playlists.remove(normalized);
            save(guildId, data);
            return new PlaylistDeleteResult(
                    PlaylistMutationStatus.SUCCESS,
                    existing.name,
                    existing.ownerId,
                    safePlaylistOwnerName(existing.ownerName)
            );
        }
    }

    public PlaylistTrackRemoveResult removePlaylistTrack(long guildId, String name, int index, Long requesterId) {
        String normalized = normalizePlaylistName(name);
        if (normalized.isBlank()) {
            return new PlaylistTrackRemoveResult(PlaylistMutationStatus.NOT_FOUND, safePlaylistDisplayName(name), index, "", 0, null, "");
        }
        int safeIndex = Math.max(1, index);
        GuildMusicData data = get(guildId);
        synchronized (data) {
            PlaylistData existing = data.playlists.get(normalized);
            if (existing == null || existing.tracks == null || existing.tracks.isEmpty()) {
                return new PlaylistTrackRemoveResult(PlaylistMutationStatus.NOT_FOUND, safePlaylistDisplayName(name), safeIndex, "", 0, null, "");
            }
            if (existing.ownerId != null && !existing.ownerId.equals(requesterId)) {
                return new PlaylistTrackRemoveResult(
                        PlaylistMutationStatus.NOT_OWNER,
                        existing.name,
                        safeIndex,
                        "",
                        existing.tracks.size(),
                        existing.ownerId,
                        safePlaylistOwnerName(existing.ownerName)
                );
            }
            int idx = safeIndex - 1;
            if (idx < 0 || idx >= existing.tracks.size()) {
                return new PlaylistTrackRemoveResult(
                        PlaylistMutationStatus.INVALID_INDEX,
                        existing.name,
                        safeIndex,
                        "",
                        existing.tracks.size(),
                        existing.ownerId,
                        safePlaylistOwnerName(existing.ownerName)
                );
            }
            PlaybackEntry removed = existing.tracks.remove(idx);
            existing.updatedAtEpochMillis = Instant.now().toEpochMilli();
            save(guildId, data);
            return new PlaylistTrackRemoveResult(
                    PlaylistMutationStatus.SUCCESS,
                    existing.name,
                    safeIndex,
                    removed == null ? "" : safePlaylistDisplayName(removed.title()),
                    existing.tracks.size(),
                    existing.ownerId,
                    safePlaylistOwnerName(existing.ownerName)
            );
        }
    }

    public PlaylistTrackAddResult addPlaylistTrack(long guildId, String name, PlaybackEntry track, Long requesterId) {
        String normalized = normalizePlaylistName(name);
        if (normalized.isBlank()) {
            return new PlaylistTrackAddResult(PlaylistMutationStatus.NOT_FOUND, safePlaylistDisplayName(name), "", 0, null, "");
        }
        if (track == null) {
            return new PlaylistTrackAddResult(PlaylistMutationStatus.EMPTY, safePlaylistDisplayName(name), "", 0, null, "");
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            PlaylistData existing = data.playlists.get(normalized);
            if (existing == null) {
                return new PlaylistTrackAddResult(PlaylistMutationStatus.NOT_FOUND, safePlaylistDisplayName(name), "", 0, null, "");
            }
            if (existing.ownerId != null && !existing.ownerId.equals(requesterId)) {
                return new PlaylistTrackAddResult(
                        PlaylistMutationStatus.NOT_OWNER,
                        existing.name,
                        "",
                        existing.tracks == null ? 0 : existing.tracks.size(),
                        existing.ownerId,
                        safePlaylistOwnerName(existing.ownerName)
                );
            }
            if (existing.tracks == null) {
                existing.tracks = new ArrayList<>();
            }
            if (existing.tracks.size() >= getPlaylistTrackLimit(guildId)) {
                return new PlaylistTrackAddResult(
                        PlaylistMutationStatus.LIMIT_REACHED,
                        existing.name,
                        "",
                        existing.tracks.size(),
                        existing.ownerId,
                        safePlaylistOwnerName(existing.ownerName)
                );
            }
            if (containsPlaylistTrack(existing.tracks, track)) {
                return new PlaylistTrackAddResult(
                        PlaylistMutationStatus.DUPLICATE,
                        existing.name,
                        safePlaylistDisplayName(track.title()),
                        existing.tracks.size(),
                        existing.ownerId,
                        safePlaylistOwnerName(existing.ownerName)
                );
            }
            existing.tracks.add(track);
            existing.updatedAtEpochMillis = Instant.now().toEpochMilli();
            save(guildId, data);
            return new PlaylistTrackAddResult(
                    PlaylistMutationStatus.SUCCESS,
                    existing.name,
                    safePlaylistDisplayName(track.title()),
                    existing.tracks.size(),
                    existing.ownerId,
                    safePlaylistOwnerName(existing.ownerName)
            );
        }
    }

    public List<PlaylistSummary> listPlaylists(long guildId) {
        return listPlaylists(guildId, null);
    }

    public List<PlaylistSummary> listPlaylists(long guildId, Long ownerIdFilter) {
        GuildMusicData data = get(guildId);
        synchronized (data) {
            List<PlaylistSummary> result = new ArrayList<>();
            for (PlaylistData playlist : data.playlists.values()) {
                if (playlist == null || playlist.name == null || playlist.name.isBlank()) {
                    continue;
                }
                if (ownerIdFilter != null && !ownerIdFilter.equals(playlist.ownerId)) {
                    continue;
                }
                result.add(new PlaylistSummary(
                        playlist.name,
                        playlist.tracks == null ? 0 : playlist.tracks.size(),
                        playlist.updatedAtEpochMillis,
                        playlist.ownerId,
                        safePlaylistOwnerName(playlist.ownerName)
                ));
            }
            result.sort(Comparator.comparingLong(PlaylistSummary::updatedAtEpochMillis).reversed()
                    .thenComparing(PlaylistSummary::name, String.CASE_INSENSITIVE_ORDER));
            return result;
        }
    }

    public PlaylistSummary getPlaylistSummary(long guildId, String name) {
        String normalized = normalizePlaylistName(name);
        if (normalized.isBlank()) {
            return null;
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            PlaylistData playlist = data.playlists.get(normalized);
            if (playlist == null || playlist.name == null || playlist.name.isBlank()) {
                return null;
            }
            return new PlaylistSummary(
                    playlist.name,
                    playlist.tracks == null ? 0 : playlist.tracks.size(),
                    playlist.updatedAtEpochMillis,
                    playlist.ownerId,
                    safePlaylistOwnerName(playlist.ownerName)
            );
        }
    }

    public List<PlaybackEntry> getPlaylistTracks(long guildId, String name) {
        String normalized = normalizePlaylistName(name);
        if (normalized.isBlank()) {
            return List.of();
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            PlaylistData playlist = data.playlists.get(normalized);
            if (playlist == null || playlist.tracks == null || playlist.tracks.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(playlist.tracks);
        }
    }

    public PlaylistShareCode exportPlaylist(long guildId, String name) {
        String normalized = normalizePlaylistName(name);
        if (normalized.isBlank()) {
            return null;
        }
        GuildMusicData data = get(guildId);
        synchronized (data) {
            cleanupExpiredPlaylistShares();
            PlaylistData playlist = data.playlists.get(normalized);
            if (playlist == null || playlist.tracks == null || playlist.tracks.isEmpty()) {
                return null;
            }
            SharedPlaylistData shared = new SharedPlaylistData();
            shared.code = generatePlaylistShareCode();
            shared.playlistName = safePlaylistDisplayName(playlist.name);
            shared.expiresAtEpochMillis = Instant.now().toEpochMilli() + PLAYLIST_SHARE_TTL_MILLIS;
            for (PlaybackEntry track : playlist.tracks) {
                if (track == null) {
                    continue;
                }
                shared.tracks.add(track);
                if (shared.tracks.size() >= getPlaylistTrackLimit(guildId)) {
                    break;
                }
            }
            if (shared.tracks.isEmpty()) {
                return null;
            }
            playlistShares.put(shared.code, shared);
            return new PlaylistShareCode(
                    shared.code,
                    shared.playlistName,
                    shared.tracks.size(),
                    shared.expiresAtEpochMillis
            );
        }
    }

    public PlaylistImportResult importPlaylist(long guildId, String code, String targetName, Long requesterId, String requesterName) {
        String normalizedCode = normalizePlaylistShareCode(code);
        if (normalizedCode.isBlank()) {
            return new PlaylistImportResult(PlaylistMutationStatus.INVALID_CODE, safePlaylistDisplayName(targetName), 0, null, "");
        }
        cleanupExpiredPlaylistShares();
        SharedPlaylistData shared = playlistShares.get(normalizedCode);
        if (shared == null || shared.tracks == null || shared.tracks.isEmpty()) {
            return new PlaylistImportResult(PlaylistMutationStatus.INVALID_CODE, safePlaylistDisplayName(targetName), 0, null, "");
        }
        String playlistName = safePlaylistDisplayName(targetName == null || targetName.isBlank() ? shared.playlistName : targetName);
        PlaylistSaveResult saved = savePlaylist(guildId, playlistName, shared.tracks, requesterId, requesterName);
        return new PlaylistImportResult(
                saved.status(),
                saved.playlistName(),
                saved.trackCount(),
                saved.ownerId(),
                saved.ownerName()
        );
    }

    private GuildMusicData get(long guildId) {
        return cache.computeIfAbsent(guildId, this::loadGuildData);
    }

    private GuildMusicData loadGuildData(long guildId) {
        GuildMusicData defaults = loadGuildDataFromYaml(file(guildId), guildId);
        if (sqliteStore == null) {
            return defaults;
        }
        try {
            SqliteMusicStore.GuildPayload payload = sqliteStore.loadGuildPayload(guildId, getPlaylistTrackLimit(guildId));
            if (payload != null) {
                defaults.history = new LinkedList<>(payload.history());
                defaults.playlists = new LinkedHashMap<>(payload.playlists());
                trimHistory(guildId, defaults);
            }
        } catch (Exception e) {
            System.out.println("[NoRule] Failed to load music sqlite data for guild " + guildId + ": " + e.getMessage());
        }
        return defaults;
    }

    @SuppressWarnings("unchecked")
    private GuildMusicData loadGuildDataFromYaml(Path file, long guildId) {
        GuildMusicData defaults = new GuildMusicData();
        if (!Files.exists(file)) {
            return defaults;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object rootObj = new Yaml().load(in);
            if (!(rootObj instanceof Map<?, ?> rootMapRaw)) {
                return defaults;
            }
            Map<String, Object> root = (Map<String, Object>) rootMapRaw;
            defaults.volume = clampVolume(readInt(root.get("volume"), 100));
            defaults.playbackSpeed = clampPlaybackSpeed(readDouble(root.get("playbackSpeed"), 1.0d));

            Object historyObj = root.get("history");
            if (historyObj instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> rawItem)) {
                        continue;
                    }
                    Map<String, Object> map = (Map<String, Object>) rawItem;
                    defaults.history.add(parsePlaybackEntryMap(map));
                }
            }
            trimHistory(guildId, defaults);

            Map<String, Object> stats = asMap(root.get("stats"));
            Map<String, Object> songCounts = asMap(stats.get("songPlayCounts"));
            for (Map.Entry<String, Object> entry : songCounts.entrySet()) {
                int count = readInt(entry.getValue(), 0);
                if (count > 0) {
                    defaults.songPlayCounts.put(entry.getKey(), count);
                }
            }
            Map<String, Object> songLabels = asMap(stats.get("songLabels"));
            for (Map.Entry<String, Object> entry : songLabels.entrySet()) {
                defaults.songLabels.put(entry.getKey(), readText(entry.getValue()));
            }
            Map<String, Object> songLastPlayedAt = asMap(stats.get("songLastPlayedAt"));
            long now = Instant.now().toEpochMilli();
            for (Map.Entry<String, Object> entry : songLastPlayedAt.entrySet()) {
                long lastPlayedAt = readLong(entry.getValue(), 0L);
                if (lastPlayedAt > 0L) {
                    defaults.songLastPlayedAt.put(entry.getKey(), lastPlayedAt);
                }
            }
            for (String songKey : defaults.songPlayCounts.keySet()) {
                defaults.songLastPlayedAt.putIfAbsent(songKey, now);
            }
            Map<String, Object> requesterCounts = asMap(stats.get("requesterCounts"));
            for (Map.Entry<String, Object> entry : requesterCounts.entrySet()) {
                Long requesterId = readNullableLong(entry.getKey());
                int count = readInt(entry.getValue(), 0);
                if (requesterId != null && count > 0) {
                    defaults.requesterCounts.put(requesterId, count);
                }
            }
            Map<String, Object> requesterLastPlayedAt = asMap(stats.get("requesterLastPlayedAt"));
            for (Map.Entry<String, Object> entry : requesterLastPlayedAt.entrySet()) {
                Long requesterId = readNullableLong(entry.getKey());
                long lastPlayedAt = readLong(entry.getValue(), 0L);
                if (requesterId != null && lastPlayedAt > 0L) {
                    defaults.requesterLastPlayedAt.put(requesterId, lastPlayedAt);
                }
            }
            for (Long requesterId : defaults.requesterCounts.keySet()) {
                defaults.requesterLastPlayedAt.putIfAbsent(requesterId, now);
            }
            defaults.todayDate = readTextOrDefault(stats.get("todayDate"), LocalDate.now().toString());
            defaults.todayPlaybackMillis = Math.max(0L, readLong(stats.get("todayPlaybackMillis"), 0L));
            rolloverDailyStats(defaults);
            pruneExpiredStats(guildId, defaults, now);

            Object playlistsObj = root.get("playlists");
            if (playlistsObj instanceof List<?> playlistList) {
                for (Object item : playlistList) {
                    if (!(item instanceof Map<?, ?> rawItem)) {
                        continue;
                    }
                    Map<String, Object> map = (Map<String, Object>) rawItem;
                    PlaylistData playlist = parsePlaylistMap(map, getPlaylistTrackLimit(guildId));
                    if (playlist == null) {
                        continue;
                    }
                    defaults.playlists.put(normalizePlaylistName(playlist.name), playlist);
                }
            }
        } catch (Exception ignored) {
        }
        return defaults;
    }

    private PlaylistData parsePlaylistMap(Map<String, Object> map, int trackLimit) {
        String playlistName = safePlaylistDisplayName(readText(map.get("name")));
        String normalized = normalizePlaylistName(playlistName);
        if (normalized.isBlank()) {
            return null;
        }
        PlaylistData playlist = new PlaylistData();
        playlist.name = playlistName;
        playlist.updatedAtEpochMillis = readLong(map.get("updatedAtEpochMillis"), 0L);
        playlist.ownerId = readNullableLong(map.get("ownerId"));
        playlist.ownerName = safePlaylistOwnerName(readText(map.get("ownerName")));
        Object tracksObj = map.get("tracks");
        if (tracksObj instanceof List<?> tracksList) {
            for (Object trackItem : tracksList) {
                if (!(trackItem instanceof Map<?, ?> rawTrack)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> trackMap = (Map<String, Object>) rawTrack;
                playlist.tracks.add(parsePlaybackEntryMap(trackMap));
                if (playlist.tracks.size() >= trackLimit) {
                    break;
                }
            }
        }
        return playlist.tracks.isEmpty() ? null : playlist;
    }

    private PlaybackEntry parsePlaybackEntryMap(Map<String, Object> map) {
        return new PlaybackEntry(
                readLong(map.get("playedAtEpochMillis"), Instant.now().toEpochMilli()),
                readText(map.get("title")),
                readText(map.get("author")),
                readText(map.get("source")),
                readText(map.get("uri")),
                readText(map.get("artworkUrl")),
                readLong(map.get("durationMillis"), 0L),
                readNullableLong(map.get("requesterId")),
                readText(map.get("requesterName"))
        );
    }

    private Map<String, Object> toPlaybackEntryMap(PlaybackEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("playedAtEpochMillis", entry.playedAtEpochMillis());
        map.put("title", entry.title());
        map.put("author", entry.author());
        map.put("source", entry.source());
        map.put("uri", entry.uri());
        map.put("artworkUrl", entry.artworkUrl());
        map.put("durationMillis", entry.durationMillis());
        map.put("requesterId", entry.requesterId() == null ? "" : String.valueOf(entry.requesterId()));
        map.put("requesterName", entry.requesterName());
        return map;
    }

    private void save(long guildId, GuildMusicData data) {
        if (sqliteStore != null) {
            try {
                sqliteStore.replaceGuildData(guildId, data.history, data.playlists);
            } catch (Exception e) {
                System.out.println("[NoRule] Failed to save music sqlite data for guild " + guildId + ": " + e.getMessage());
            }
        }

        Path file = file(guildId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("guildId", String.valueOf(guildId));
        root.put("volume", clampVolume(data.volume));
        root.put("playbackSpeed", clampPlaybackSpeed(data.playbackSpeed));

        if (sqliteStore == null) {
            List<Map<String, Object>> history = new ArrayList<>();
            for (PlaybackEntry entry : data.history) {
                history.add(toPlaybackEntryMap(entry));
            }
            root.put("history", history);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("songPlayCounts", data.songPlayCounts);
        stats.put("songLabels", data.songLabels);
        stats.put("songLastPlayedAt", data.songLastPlayedAt);
        Map<String, Object> requesterCounts = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : data.requesterCounts.entrySet()) {
            requesterCounts.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        stats.put("requesterCounts", requesterCounts);
        Map<String, Object> requesterLastPlayedAt = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> entry : data.requesterLastPlayedAt.entrySet()) {
            requesterLastPlayedAt.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        stats.put("requesterLastPlayedAt", requesterLastPlayedAt);
        stats.put("todayDate", data.todayDate);
        stats.put("todayPlaybackMillis", data.todayPlaybackMillis);
        root.put("stats", stats);

        if (sqliteStore == null) {
            List<Map<String, Object>> playlists = new ArrayList<>();
            for (PlaylistData playlist : data.playlists.values()) {
                if (playlist == null || playlist.name == null || playlist.name.isBlank()
                        || playlist.tracks == null || playlist.tracks.isEmpty()) {
                    continue;
                }
                Map<String, Object> playlistMap = new LinkedHashMap<>();
                playlistMap.put("name", playlist.name);
                playlistMap.put("updatedAtEpochMillis", playlist.updatedAtEpochMillis);
                playlistMap.put("ownerId", playlist.ownerId == null ? "" : String.valueOf(playlist.ownerId));
                playlistMap.put("ownerName", safePlaylistOwnerName(playlist.ownerName));
                List<Map<String, Object>> tracks = new ArrayList<>();
                for (PlaybackEntry entry : playlist.tracks) {
                    tracks.add(toPlaybackEntryMap(entry));
                }
                playlistMap.put("tracks", tracks);
                playlists.add(playlistMap);
            }
            root.put("playlists", playlists);
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Yaml yaml = new Yaml(options);
        try (Writer writer = Files.newBufferedWriter(file)) {
            yaml.dump(root, writer);
        } catch (Exception ignored) {
        }
    }

    private void rolloverDailyStats(GuildMusicData data) {
        String today = LocalDate.now().toString();
        if (!today.equals(data.todayDate)) {
            data.todayDate = today;
            data.todayPlaybackMillis = 0L;
        }
    }

    private void trimHistory(long guildId, GuildMusicData data) {
        int historyLimit = getHistoryLimit(guildId);
        while (data.history.size() > historyLimit) {
            data.history.removeLast();
        }
    }

    private void pruneExpiredStats(long guildId, GuildMusicData data, long now) {
        int statsRetentionDays = getStatsRetentionDays(guildId);
        if (statsRetentionDays <= 0) {
            return;
        }
        long cutoff = now - statsRetentionDays * 86_400_000L;
        data.songLastPlayedAt.entrySet().removeIf(entry -> {
            long lastPlayedAt = entry.getValue() == null ? 0L : entry.getValue();
            if (lastPlayedAt > cutoff) {
                return false;
            }
            data.songPlayCounts.remove(entry.getKey());
            data.songLabels.remove(entry.getKey());
            return true;
        });
        data.songPlayCounts.keySet().removeIf(songKey -> !data.songLastPlayedAt.containsKey(songKey));
        data.songLabels.keySet().removeIf(songKey -> !data.songPlayCounts.containsKey(songKey));

        data.requesterLastPlayedAt.entrySet().removeIf(entry -> {
            long lastPlayedAt = entry.getValue() == null ? 0L : entry.getValue();
            if (lastPlayedAt > cutoff) {
                return false;
            }
            data.requesterCounts.remove(entry.getKey());
            return true;
        });
        data.requesterCounts.keySet().removeIf(requesterId -> !data.requesterLastPlayedAt.containsKey(requesterId));
    }

    private int getPlaylistTrackLimit(long guildId) {
        try {
            return Math.max(1, playlistTrackLimitProvider.applyAsInt(guildId));
        } catch (Exception ignored) {
            return 100;
        }
    }

    private int getHistoryLimit(long guildId) {
        try {
            return Math.max(1, historyLimitProvider.applyAsInt(guildId));
        } catch (Exception ignored) {
            return 100;
        }
    }

    private int getStatsRetentionDays(long guildId) {
        try {
            return Math.max(0, statsRetentionDaysProvider.applyAsInt(guildId));
        } catch (Exception ignored) {
            return 30;
        }
    }

    private Path file(long guildId) {
        return dir.resolve(guildId + ".yml");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private int readInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private long readLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private Long readNullableLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String readTextOrDefault(Object value, String defaultValue) {
        String text = readText(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    private int clampVolume(int volume) {
        return Math.max(1, Math.min(100, volume));
    }

    private double clampPlaybackSpeed(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            return 1.0d;
        }
        return Math.max(0.5d, Math.min(2.0d, speed));
    }

    private double readDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private void cleanupExpiredPlaylistShares() {
        long now = Instant.now().toEpochMilli();
        playlistShares.entrySet().removeIf(entry -> {
            SharedPlaylistData shared = entry.getValue();
            return shared == null || shared.expiresAtEpochMillis <= now;
        });
    }

    private String generatePlaylistShareCode() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));
            SharedPlaylistData existing = playlistShares.get(code);
            if (existing == null || existing.expiresAtEpochMillis <= Instant.now().toEpochMilli()) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to allocate playlist share code");
    }

    private String normalizePlaylistName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsPlaylistTrack(List<PlaybackEntry> tracks, PlaybackEntry candidate) {
        if (tracks == null || tracks.isEmpty() || candidate == null) {
            return false;
        }
        String candidateUri = normalizeTrackUri(candidate.uri());
        String candidateKey = candidate.songKey();
        for (PlaybackEntry existing : tracks) {
            if (existing == null) {
                continue;
            }
            String existingUri = normalizeTrackUri(existing.uri());
            if (!candidateUri.isBlank() && !existingUri.isBlank() && candidateUri.equals(existingUri)) {
                return true;
            }
            if (!candidateKey.isBlank() && candidateKey.equals(existing.songKey())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeTrackUri(String uri) {
        return uri == null ? "" : uri.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePlaylistShareCode(String code) {
        if (code == null) {
            return "";
        }
        String digits = code.trim();
        return digits.matches("\\d{6}") ? digits : "";
    }

    private String safePlaylistDisplayName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.length() <= 60) {
            return trimmed;
        }
        return trimmed.substring(0, 60);
    }

    private String safePlaylistOwnerName(String ownerName) {
        if (ownerName == null) {
            return "-";
        }
        String trimmed = ownerName.trim();
        if (trimmed.isBlank()) {
            return "-";
        }
        if (trimmed.length() <= 60) {
            return trimmed;
        }
        return trimmed.substring(0, 60);
    }

    private final class SqliteMusicStore {
        private static final String SQL_CREATE_PLAY_HISTORY = """
                CREATE TABLE IF NOT EXISTS play_history (
                    guild_id INTEGER NOT NULL,
                    history_index INTEGER NOT NULL,
                    played_at_epoch_millis INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT NOT NULL,
                    source TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    artwork_url TEXT NOT NULL,
                    duration_millis INTEGER NOT NULL,
                    requester_id TEXT NOT NULL,
                    requester_name TEXT NOT NULL,
                    PRIMARY KEY (guild_id, history_index)
                )
                """;
        private static final String SQL_CREATE_USER_PLAYLISTS = """
                CREATE TABLE IF NOT EXISTS user_playlists (
                    guild_id INTEGER NOT NULL,
                    normalized_name TEXT NOT NULL,
                    playlist_name TEXT NOT NULL,
                    updated_at_epoch_millis INTEGER NOT NULL,
                    owner_id TEXT NOT NULL,
                    owner_name TEXT NOT NULL,
                    tracks_yaml TEXT NOT NULL,
                    PRIMARY KEY (guild_id, normalized_name)
                )
                """;
        private static final String SQL_CREATE_PLAY_HISTORY_GUILD_INDEX = """
                CREATE INDEX IF NOT EXISTS idx_play_history_guild
                ON play_history(guild_id)
                """;
        private static final String SQL_CREATE_USER_PLAYLISTS_GUILD_INDEX = """
                CREATE INDEX IF NOT EXISTS idx_user_playlists_guild
                ON user_playlists(guild_id)
                """;
        private static final String SQL_DELETE_PLAY_HISTORY_BY_GUILD = "DELETE FROM play_history WHERE guild_id = ?";
        private static final String SQL_DELETE_USER_PLAYLISTS_BY_GUILD = "DELETE FROM user_playlists WHERE guild_id = ?";
        private static final String SQL_INSERT_PLAY_HISTORY = """
                INSERT INTO play_history (
                    guild_id, history_index, played_at_epoch_millis, title, author, source, uri,
                    artwork_url, duration_millis, requester_id, requester_name
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        private static final String SQL_INSERT_USER_PLAYLISTS = """
                INSERT INTO user_playlists (
                    guild_id, normalized_name, playlist_name, updated_at_epoch_millis, owner_id, owner_name, tracks_yaml
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        private static final String SQL_SELECT_PLAY_HISTORY = """
                SELECT history_index, played_at_epoch_millis, title, author, source, uri,
                       artwork_url, duration_millis, requester_id, requester_name
                FROM play_history
                WHERE guild_id = ?
                ORDER BY history_index ASC
                """;
        private static final String SQL_SELECT_USER_PLAYLISTS = """
                SELECT normalized_name, playlist_name, updated_at_epoch_millis, owner_id, owner_name, tracks_yaml
                FROM user_playlists
                WHERE guild_id = ?
                ORDER BY normalized_name ASC
                """;

        private final String jdbcUrl;

        private record GuildPayload(List<PlaybackEntry> history, Map<String, PlaylistData> playlists) {
        }

        private SqliteMusicStore(Path dbFilePath) {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("SQLite JDBC driver not found (org.sqlite.JDBC)", e);
            }
            try {
                Path parent = dbFilePath.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to prepare sqlite directory for music data", e);
            }
            this.jdbcUrl = "jdbc:sqlite:" + dbFilePath.toAbsolutePath().normalize();
            initializeSchema();
        }

        private void initializeSchema() {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 Statement statement = connection.createStatement()) {
                statement.execute(SQL_CREATE_PLAY_HISTORY);
                statement.execute(SQL_CREATE_USER_PLAYLISTS);
                statement.execute(SQL_CREATE_PLAY_HISTORY_GUILD_INDEX);
                statement.execute(SQL_CREATE_USER_PLAYLISTS_GUILD_INDEX);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize music sqlite schema", e);
            }
        }

        private GuildPayload loadGuildPayload(long guildId, int playlistTrackLimit) throws SQLException {
            List<PlaybackEntry> history = new ArrayList<>();
            Map<String, PlaylistData> playlists = new LinkedHashMap<>();

            try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
                try (PreparedStatement historyStmt = connection.prepareStatement(SQL_SELECT_PLAY_HISTORY)) {
                    historyStmt.setLong(1, guildId);
                    try (ResultSet rs = historyStmt.executeQuery()) {
                        while (rs.next()) {
                            history.add(new PlaybackEntry(
                                    rs.getLong("played_at_epoch_millis"),
                                    rs.getString("title"),
                                    rs.getString("author"),
                                    rs.getString("source"),
                                    rs.getString("uri"),
                                    rs.getString("artwork_url"),
                                    rs.getLong("duration_millis"),
                                    readNullableLong(rs.getString("requester_id")),
                                    rs.getString("requester_name")
                            ));
                        }
                    }
                }

                try (PreparedStatement playlistStmt = connection.prepareStatement(SQL_SELECT_USER_PLAYLISTS)) {
                    playlistStmt.setLong(1, guildId);
                    try (ResultSet rs = playlistStmt.executeQuery()) {
                        while (rs.next()) {
                            PlaylistData playlist = new PlaylistData();
                            playlist.name = safePlaylistDisplayName(rs.getString("playlist_name"));
                            playlist.updatedAtEpochMillis = rs.getLong("updated_at_epoch_millis");
                            playlist.ownerId = readNullableLong(rs.getString("owner_id"));
                            playlist.ownerName = safePlaylistOwnerName(rs.getString("owner_name"));
                            playlist.tracks = parseTracksYaml(rs.getString("tracks_yaml"), playlistTrackLimit);
                            if (playlist.tracks.isEmpty()) {
                                continue;
                            }
                            String normalized = normalizePlaylistName(rs.getString("normalized_name"));
                            if (normalized.isBlank()) {
                                normalized = normalizePlaylistName(playlist.name);
                            }
                            if (normalized.isBlank()) {
                                continue;
                            }
                            playlists.put(normalized, playlist);
                        }
                    }
                }
            }

            if (history.isEmpty() && playlists.isEmpty()) {
                return null;
            }
            return new GuildPayload(history, playlists);
        }

        private void replaceGuildData(long guildId, List<PlaybackEntry> history, Map<String, PlaylistData> playlists) throws SQLException {
            try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement deleteHistory = connection.prepareStatement(SQL_DELETE_PLAY_HISTORY_BY_GUILD);
                         PreparedStatement deletePlaylists = connection.prepareStatement(SQL_DELETE_USER_PLAYLISTS_BY_GUILD)) {
                        deleteHistory.setLong(1, guildId);
                        deleteHistory.executeUpdate();
                        deletePlaylists.setLong(1, guildId);
                        deletePlaylists.executeUpdate();
                    }

                    try (PreparedStatement insertHistory = connection.prepareStatement(SQL_INSERT_PLAY_HISTORY)) {
                        int index = 0;
                        for (PlaybackEntry entry : history) {
                            if (entry == null) {
                                continue;
                            }
                            insertHistory.setLong(1, guildId);
                            insertHistory.setInt(2, index++);
                            insertHistory.setLong(3, entry.playedAtEpochMillis());
                            insertHistory.setString(4, readText(entry.title()));
                            insertHistory.setString(5, readText(entry.author()));
                            insertHistory.setString(6, readText(entry.source()));
                            insertHistory.setString(7, readText(entry.uri()));
                            insertHistory.setString(8, readText(entry.artworkUrl()));
                            insertHistory.setLong(9, Math.max(0L, entry.durationMillis()));
                            insertHistory.setString(10, entry.requesterId() == null ? "" : String.valueOf(entry.requesterId()));
                            insertHistory.setString(11, readText(entry.requesterName()));
                            insertHistory.addBatch();
                        }
                        insertHistory.executeBatch();
                    }

                    try (PreparedStatement insertPlaylists = connection.prepareStatement(SQL_INSERT_USER_PLAYLISTS)) {
                        for (Map.Entry<String, PlaylistData> entry : playlists.entrySet()) {
                            String normalized = normalizePlaylistName(entry.getKey());
                            PlaylistData playlist = entry.getValue();
                            if (normalized.isBlank() || playlist == null || playlist.tracks == null || playlist.tracks.isEmpty()) {
                                continue;
                            }
                            insertPlaylists.setLong(1, guildId);
                            insertPlaylists.setString(2, normalized);
                            insertPlaylists.setString(3, safePlaylistDisplayName(playlist.name));
                            insertPlaylists.setLong(4, Math.max(0L, playlist.updatedAtEpochMillis));
                            insertPlaylists.setString(5, playlist.ownerId == null ? "" : String.valueOf(playlist.ownerId));
                            insertPlaylists.setString(6, safePlaylistOwnerName(playlist.ownerName));
                            insertPlaylists.setString(7, buildTracksYaml(playlist.tracks));
                            insertPlaylists.addBatch();
                        }
                        insertPlaylists.executeBatch();
                    }
                    connection.commit();
                } catch (Exception ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        }

        private String buildTracksYaml(List<PlaybackEntry> tracks) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PlaybackEntry track : tracks) {
                if (track == null) {
                    continue;
                }
                rows.add(toPlaybackEntryMap(track));
            }
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            return new Yaml(options).dump(rows);
        }

        @SuppressWarnings("unchecked")
        private List<PlaybackEntry> parseTracksYaml(String raw, int playlistTrackLimit) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            List<PlaybackEntry> tracks = new ArrayList<>();
            try {
                Object loaded = new Yaml().load(raw);
                if (!(loaded instanceof List<?> list)) {
                    return List.of();
                }
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> rawMap)) {
                        continue;
                    }
                    Map<String, Object> map = (Map<String, Object>) rawMap;
                    tracks.add(parsePlaybackEntryMap(map));
                    if (tracks.size() >= Math.max(1, playlistTrackLimit)) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                return List.of();
            }
            return tracks;
        }
    }
}



