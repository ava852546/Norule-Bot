package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PrivateRoomListener extends ListenerAdapter {
    private static final long ROOM_OWNER_PERMISSION_RAW = Permission.getRaw(
            Permission.MANAGE_CHANNEL,
            Permission.VOICE_MOVE_OTHERS,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.VOICE_STREAM,
            Permission.VOICE_USE_VAD,
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.USE_APPLICATION_COMMANDS
    );
    private final GuildSettingsService settingsService;
    private final I18nService i18n;
    private final Map<Long, Set<Long>> privateChannelsByGuild = new ConcurrentHashMap<>();
    private static final Map<Long, Set<Long>> PRIVATE_CHANNELS = new ConcurrentHashMap<>();
    private static final Map<Long, Map<Long, Long>> ROOM_OWNERS = new ConcurrentHashMap<>();
    private static volatile PrivateRoomListener activeInstance;
    private final Path cacheFile;
    private final Object cacheLock = new Object();

    public PrivateRoomListener(GuildSettingsService settingsService, I18nService i18n) {
        this.settingsService = settingsService;
        this.i18n = i18n;
        this.cacheFile = settingsService.getSettingsDirectory().resolve("private-room-cache.yml");
        loadCacheFromDisk();
        activeInstance = this;
    }

    @Override
    public void onReady(ReadyEvent event) {
        reconcileCachedRooms(event.getJDA().getGuilds());
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        handleCreate(event);
        handleCleanup(event);
    }

    private void handleCreate(GuildVoiceUpdateEvent event) {
        AudioChannel joined = event.getChannelJoined();
        if (joined == null || event.getEntity().getUser().isBot()) {
            return;
        }

        BotConfig.PrivateRoom cfg = settingsService.getPrivateRoom(event.getGuild().getIdLong());
        if (!cfg.isEnabled() || cfg.getTriggerVoiceChannelId() == null) {
            return;
        }
        if (joined.getIdLong() != cfg.getTriggerVoiceChannelId()) {
            return;
        }

        Member member = event.getEntity();
        String roomName = buildRoomName(member);
        event.getGuild().createVoiceChannel(roomName)
                .setParent(resolveCategory(event, cfg))
                .setUserlimit(cfg.getUserLimit())
                .setBitrate(resolveMaxBitrate(event))
                .addRolePermissionOverride(
                        event.getGuild().getPublicRole().getIdLong(),
                        Permission.getRaw(
                                Permission.CREATE_INSTANT_INVITE,
                                Permission.VIEW_CHANNEL,
                                Permission.VOICE_CONNECT,
                                Permission.VOICE_SPEAK,
                                Permission.VOICE_STREAM,
                                Permission.VOICE_USE_VAD
                        ),
                        0L
                )
                .addMemberPermissionOverride(
                        member.getIdLong(),
                        ROOM_OWNER_PERMISSION_RAW,
                        0L
                )
                .queue(created -> {
                    rememberPrivateChannel(created);
                    ROOM_OWNERS
                            .computeIfAbsent(created.getGuild().getIdLong(), id -> new ConcurrentHashMap<>())
                            .put(created.getIdLong(), member.getIdLong());
                    persistCacheQuietly();
                    event.getGuild().moveVoiceMember(member, created).queue();
                });
    }

    private int resolveMaxBitrate(GuildVoiceUpdateEvent event) {
        int max = event.getGuild().getMaxBitrate();
        return max > 0 ? max : 64000;
    }

    private void handleCleanup(GuildVoiceUpdateEvent event) {
        AudioChannel left = event.getChannelLeft();
        if (!(left instanceof VoiceChannel voice)) {
            return;
        }
        Set<Long> set = privateChannelsByGuild.get(event.getGuild().getIdLong());
        if (set == null || !set.contains(voice.getIdLong())) {
            return;
        }
        if (!voice.getMembers().isEmpty()) {
            return;
        }
        voice.delete().queue(v -> {
            removeManagedPrivateChannel(event.getGuild().getIdLong(), voice.getIdLong());
            persistCacheQuietly();
        }, e -> {
        });
    }

    private Category resolveCategory(GuildVoiceUpdateEvent event, BotConfig.PrivateRoom cfg) {
        if (cfg.getTriggerVoiceChannelId() != null) {
            AudioChannel trigger = event.getGuild().getVoiceChannelById(cfg.getTriggerVoiceChannelId());
            if (trigger == null) {
                trigger = event.getGuild().getStageChannelById(cfg.getTriggerVoiceChannelId());
            }
            if (trigger instanceof ICategorizableChannel) {
                ICategorizableChannel categorizable = (ICategorizableChannel) trigger;
                if (categorizable.getParentCategory() != null) {
                    return categorizable.getParentCategory();
                }
            }
        }
        AudioChannel joined = event.getChannelJoined();
        if (joined instanceof ICategorizableChannel) {
            ICategorizableChannel categorizable = (ICategorizableChannel) joined;
            if (categorizable.getParentCategory() != null) {
                return categorizable.getParentCategory();
            }
        }
        return null;
    }

    private void rememberPrivateChannel(VoiceChannel channel) {
        long guildId = channel.getGuild().getIdLong();
        long channelId = channel.getIdLong();
        privateChannelsByGuild.computeIfAbsent(guildId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
        PRIVATE_CHANNELS.computeIfAbsent(guildId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
    }

    private void reconcileCachedRooms(List<Guild> guilds) {
        Map<Long, Guild> guildById = new HashMap<>();
        for (Guild guild : guilds) {
            guildById.put(guild.getIdLong(), guild);
        }

        Map<Long, Set<Long>> snapshot = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : privateChannelsByGuild.entrySet()) {
            snapshot.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }

        boolean changed = false;
        for (Map.Entry<Long, Set<Long>> entry : snapshot.entrySet()) {
            long guildId = entry.getKey();
            Guild guild = guildById.get(guildId);
            if (guild == null) {
                for (Long channelId : entry.getValue()) {
                    if (removeManagedPrivateChannel(guildId, channelId)) {
                        changed = true;
                    }
                }
                continue;
            }

            for (Long channelId : entry.getValue()) {
                VoiceChannel channel = guild.getVoiceChannelById(channelId);
                if (channel == null) {
                    if (removeManagedPrivateChannel(guildId, channelId)) {
                        changed = true;
                    }
                    continue;
                }
                if (channel.getMembers().isEmpty()) {
                    long targetChannelId = channelId;
                    channel.delete().queue(
                            success -> {
                                removeManagedPrivateChannel(guildId, targetChannelId);
                                persistCacheQuietly();
                            },
                            error -> {
                            }
                    );
                }
            }
        }

        if (changed) {
            persistCacheQuietly();
        }
    }

    private boolean removeManagedPrivateChannel(long guildId, long channelId) {
        boolean changed = false;
        Set<Long> localSet = privateChannelsByGuild.get(guildId);
        if (localSet != null) {
            changed = localSet.remove(channelId) || changed;
            if (localSet.isEmpty()) {
                privateChannelsByGuild.remove(guildId);
            }
        }
        Set<Long> globalSet = PRIVATE_CHANNELS.get(guildId);
        if (globalSet != null) {
            changed = globalSet.remove(channelId) || changed;
            if (globalSet.isEmpty()) {
                PRIVATE_CHANNELS.remove(guildId);
            }
        }
        Map<Long, Long> owners = ROOM_OWNERS.get(guildId);
        if (owners != null) {
            changed = owners.remove(channelId) != null || changed;
            if (owners.isEmpty()) {
                ROOM_OWNERS.remove(guildId);
            }
        }
        return changed;
    }

    private void loadCacheFromDisk() {
        synchronized (cacheLock) {
            if (!Files.exists(cacheFile)) {
                return;
            }
            try (InputStream input = Files.newInputStream(cacheFile)) {
                Object loaded = new Yaml().load(input);
                Map<String, Object> root = asMap(loaded);
                privateChannelsByGuild.clear();
                PRIVATE_CHANNELS.clear();
                ROOM_OWNERS.clear();
                restorePrivateChannels(asMap(root.get("privateChannels")));
                restoreRoomOwners(asMap(root.get("roomOwners")));
            } catch (Exception e) {
                System.err.println("[NoRule] Failed to load private room cache: " + e.getMessage());
            }
        }
    }

    private void restorePrivateChannels(Map<String, Object> channelsMap) {
        for (Map.Entry<String, Object> entry : channelsMap.entrySet()) {
            Long guildId = parseLong(entry.getKey());
            if (guildId == null) {
                continue;
            }
            Set<Long> channelIds = ConcurrentHashMap.newKeySet();
            Object value = entry.getValue();
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    Long channelId = parseLong(item);
                    if (channelId != null) {
                        channelIds.add(channelId);
                    }
                }
            } else {
                Long channelId = parseLong(value);
                if (channelId != null) {
                    channelIds.add(channelId);
                }
            }
            if (!channelIds.isEmpty()) {
                privateChannelsByGuild.put(guildId, channelIds);
                Set<Long> globalSet = ConcurrentHashMap.newKeySet();
                globalSet.addAll(channelIds);
                PRIVATE_CHANNELS.put(guildId, globalSet);
            }
        }
    }

    private void restoreRoomOwners(Map<String, Object> ownersRoot) {
        for (Map.Entry<String, Object> guildEntry : ownersRoot.entrySet()) {
            Long guildId = parseLong(guildEntry.getKey());
            if (guildId == null) {
                continue;
            }
            Map<String, Object> ownersByChannel = asMap(guildEntry.getValue());
            if (ownersByChannel.isEmpty()) {
                continue;
            }
            Map<Long, Long> owners = new ConcurrentHashMap<>();
            for (Map.Entry<String, Object> ownerEntry : ownersByChannel.entrySet()) {
                Long channelId = parseLong(ownerEntry.getKey());
                Long ownerId = parseLong(ownerEntry.getValue());
                if (channelId != null && ownerId != null) {
                    owners.put(channelId, ownerId);
                }
            }
            if (!owners.isEmpty()) {
                ROOM_OWNERS.put(guildId, owners);
            }
        }
    }

    private void persistCacheQuietly() {
        synchronized (cacheLock) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("privateChannels", snapshotPrivateChannels());
            root.put("roomOwners", snapshotRoomOwners());
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            Yaml yaml = new Yaml(options);
            try {
                Files.createDirectories(cacheFile.getParent());
            } catch (IOException ignored) {
            }
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                yaml.dump(root, writer);
            } catch (IOException e) {
                System.err.println("[NoRule] Failed to persist private room cache: " + e.getMessage());
            }
        }
    }

    private Map<String, Object> snapshotPrivateChannels() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : privateChannelsByGuild.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            List<String> channelIds = entry.getValue().stream().map(String::valueOf).sorted().toList();
            map.put(String.valueOf(entry.getKey()), channelIds);
        }
        return map;
    }

    private Map<String, Object> snapshotRoomOwners() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<Long, Long>> entry : ROOM_OWNERS.entrySet()) {
            Map<Long, Long> owners = entry.getValue();
            if (owners == null || owners.isEmpty()) {
                continue;
            }
            Map<String, Object> guildOwners = new LinkedHashMap<>();
            for (Map.Entry<Long, Long> ownerEntry : owners.entrySet()) {
                guildOwners.put(String.valueOf(ownerEntry.getKey()), String.valueOf(ownerEntry.getValue()));
            }
            map.put(String.valueOf(entry.getKey()), guildOwners);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String text = String.valueOf(value).trim();
            if (text.isBlank()) {
                return null;
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isManagedPrivateRoom(long guildId, long channelId) {
        Set<Long> rooms = PRIVATE_CHANNELS.get(guildId);
        return rooms != null && rooms.contains(channelId);
    }

    public static boolean isRoomOwner(long guildId, long channelId, long userId) {
        Map<Long, Long> owners = ROOM_OWNERS.get(guildId);
        if (owners == null) {
            return false;
        }
        Long ownerId = owners.get(channelId);
        return ownerId != null && ownerId == userId;
    }

    public static Long getRoomOwnerId(long guildId, long channelId) {
        Map<Long, Long> owners = ROOM_OWNERS.get(guildId);
        return owners == null ? null : owners.get(channelId);
    }

    public static void setRoomOwner(long guildId, long channelId, long userId) {
        ROOM_OWNERS.computeIfAbsent(guildId, id -> new ConcurrentHashMap<>()).put(channelId, userId);
        PrivateRoomListener listener = activeInstance;
        if (listener != null) {
            listener.persistCacheQuietly();
        }
    }

    public static long getRoomOwnerPermissionRaw() {
        return ROOM_OWNER_PERMISSION_RAW;
    }

    private String buildRoomName(Member member) {
        String lang = settingsService.getLanguage(member.getGuild().getIdLong());
        String base = member.getEffectiveName();
        if (base == null || base.isBlank()) {
            base = member.getUser().getName();
        }
        String roomName = base + " " + i18n.t(lang, "room_settings.default_room_suffix");
        return roomName.length() > 100 ? roomName.substring(0, 100) : roomName;
    }
}


