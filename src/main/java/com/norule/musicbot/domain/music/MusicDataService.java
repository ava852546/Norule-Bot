package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.*;

import com.norule.musicbot.*;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.function.LongFunction;

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
        INVALID_INDEX
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

    private static final int MAX_PLAYLIST_TRACKS = 100;
    private static final long PLAYLIST_SHARE_TTL_MILLIS = 180_000L;

    private final Path dir;
    private final LongFunction<BotConfig.Music> musicConfigResolver;
    private final Map<Long, GuildMusicData> cache = new ConcurrentHashMap<>();
    private final Map<String, SharedPlaylistData> playlistShares = new ConcurrentHashMap<>();

    public MusicDataService(Path dir, LongFunction<BotConfig.Music> musicConfigResolver) {
        this.dir = dir;
        this.musicConfigResolver = musicConfigResolver == null ? ignored -> BotConfig.Music.defaultValues() : musicConfigResolver;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create music data directory: " + dir.toAbsolutePath(), e);
        }
    }

    public void reloadAll() {
        cache.clear();
        playlistShares.clear();
    }

    public void reload(long guildId) {
        cache.remove(guildId);
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
            if (existing != null && existing.ownerId != null && !existing.ownerId.equals(requesterId)) {
                return new PlaylistSaveResult(
                        PlaylistMutationStatus.NAME_CONFLICT,
                        existing.name,
                        existing.tracks == null ? 0 : existing.tracks.size(),
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
                if (track == null) {
                    continue;
                }
                playlist.tracks.add(track);
                if (playlist.tracks.size() >= MAX_PLAYLIST_TRACKS) {
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
                if (shared.tracks.size() >= MAX_PLAYLIST_TRACKS) {
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

    @SuppressWarnings("unchecked")
    private GuildMusicData loadGuildData(long guildId) {
        Path file = file(guildId);
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

            Object historyObj = root.get("history");
            if (historyObj instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> rawItem)) {
                        continue;
                    }
                    Map<String, Object> map = (Map<String, Object>) rawItem;
                    defaults.history.add(new PlaybackEntry(
                            readLong(map.get("playedAtEpochMillis"), Instant.now().toEpochMilli()),
                            readText(map.get("title")),
                            readText(map.get("author")),
                            readText(map.get("source")),
                            readText(map.get("uri")),
                            readText(map.get("artworkUrl")),
                            readLong(map.get("durationMillis"), 0L),
                            readNullableLong(map.get("requesterId")),
                            readText(map.get("requesterName"))
                    ));
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
                    String playlistName = safePlaylistDisplayName(readText(map.get("name")));
                    String normalized = normalizePlaylistName(playlistName);
                    if (normalized.isBlank()) {
                        continue;
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
                            Map<String, Object> trackMap = (Map<String, Object>) rawTrack;
                            playlist.tracks.add(new PlaybackEntry(
                                    readLong(trackMap.get("playedAtEpochMillis"), Instant.now().toEpochMilli()),
                                    readText(trackMap.get("title")),
                                    readText(trackMap.get("author")),
                                    readText(trackMap.get("source")),
                                    readText(trackMap.get("uri")),
                                    readText(trackMap.get("artworkUrl")),
                                    readLong(trackMap.get("durationMillis"), 0L),
                                    readNullableLong(trackMap.get("requesterId")),
                                    readText(trackMap.get("requesterName"))
                            ));
                            if (playlist.tracks.size() >= MAX_PLAYLIST_TRACKS) {
                                break;
                            }
                        }
                    }
                    if (!playlist.tracks.isEmpty()) {
                        defaults.playlists.put(normalized, playlist);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return defaults;
    }

    private void save(long guildId, GuildMusicData data) {
        Path file = file(guildId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("guildId", String.valueOf(guildId));
        root.put("volume", clampVolume(data.volume));

        List<Map<String, Object>> history = new ArrayList<>();
        for (PlaybackEntry entry : data.history) {
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
            history.add(map);
        }
        root.put("history", history);

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
                tracks.add(map);
            }
            playlistMap.put("tracks", tracks);
            playlists.add(playlistMap);
        }
        root.put("playlists", playlists);

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
        int historyLimit = getMusicConfig(guildId).getHistoryLimit();
        while (data.history.size() > historyLimit) {
            data.history.removeLast();
        }
    }

    private void pruneExpiredStats(long guildId, GuildMusicData data, long now) {
        int statsRetentionDays = getMusicConfig(guildId).getStatsRetentionDays();
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

    private BotConfig.Music getMusicConfig(long guildId) {
        try {
            BotConfig.Music config = musicConfigResolver.apply(guildId);
            return config == null ? BotConfig.Music.defaultValues() : config;
        } catch (Exception ignored) {
            return BotConfig.Music.defaultValues();
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
        return Math.max(0, Math.min(200, volume));
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
}


