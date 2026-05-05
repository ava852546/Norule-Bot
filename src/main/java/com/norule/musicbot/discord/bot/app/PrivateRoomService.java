package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PrivateRoomService {
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
    private final PrivateRoomCacheRepository cacheRepository;
    private final Map<Long, Set<Long>> privateChannelsByGuild = new ConcurrentHashMap<>();
    private static final Map<Long, Set<Long>> PRIVATE_CHANNELS = new ConcurrentHashMap<>();
    private static final Map<Long, Map<Long, Long>> ROOM_OWNERS = new ConcurrentHashMap<>();
    private static volatile PrivateRoomService activeInstance;

    public PrivateRoomService(GuildSettingsService settingsService,
                              I18nService i18n,
                              PrivateRoomCacheRepository cacheRepository) {
        this.settingsService = settingsService;
        this.i18n = i18n;
        this.cacheRepository = cacheRepository;
        loadCacheFromRepository();
        activeInstance = this;
    }

    public void onReady(ReadyEvent event) {
        reconcileCachedRooms(event.getJDA().getGuilds());
    }

    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        handleCreate(event);
        handleCleanup(event);
    }

    private void handleCreate(GuildVoiceUpdateEvent event) {
        AudioChannel joined = event.getChannelJoined();
        if (joined == null || event.getEntity().getUser().isBot()) {
            return;
        }

        var cfg = settingsService.getPrivateRoom(event.getGuild().getIdLong());
        if (!cfg.isEnabled() || cfg.getTriggerVoiceChannelId() == null) {
            return;
        }
        if (joined.getIdLong() != cfg.getTriggerVoiceChannelId()) {
            return;
        }

        Member member = event.getEntity();
        String roomName = buildRoomName(member);
        event.getGuild().createVoiceChannel(roomName)
                .setParent(resolveCategory(event, cfg.getTriggerVoiceChannelId()))
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
                    long guildId = created.getGuild().getIdLong();
                    long channelId = created.getIdLong();
                    long ownerId = member.getIdLong();
                    rememberPrivateChannel(created);
                    ROOM_OWNERS
                            .computeIfAbsent(guildId, id -> new ConcurrentHashMap<>())
                            .put(channelId, ownerId);
                    upsertCacheEntry(guildId, channelId, ownerId);
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
            long guildId = event.getGuild().getIdLong();
            long channelId = voice.getIdLong();
            removeManagedPrivateChannel(guildId, channelId);
            removeCacheEntry(guildId, channelId);
        }, e -> {
        });
    }

    private Category resolveCategory(GuildVoiceUpdateEvent event, Long triggerVoiceChannelId) {
        if (triggerVoiceChannelId != null) {
            AudioChannel trigger = event.getGuild().getVoiceChannelById(triggerVoiceChannelId);
            if (trigger == null) {
                trigger = event.getGuild().getStageChannelById(triggerVoiceChannelId);
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

        for (Map.Entry<Long, Set<Long>> entry : snapshot.entrySet()) {
            long guildId = entry.getKey();
            Guild guild = guildById.get(guildId);
            if (guild == null) {
                for (Long channelId : entry.getValue()) {
                    if (removeManagedPrivateChannel(guildId, channelId)) {
                        removeCacheEntry(guildId, channelId);
                    }
                }
                continue;
            }

            for (Long channelId : entry.getValue()) {
                VoiceChannel channel = guild.getVoiceChannelById(channelId);
                if (channel == null) {
                    if (removeManagedPrivateChannel(guildId, channelId)) {
                        removeCacheEntry(guildId, channelId);
                    }
                    continue;
                }
                if (channel.getMembers().isEmpty()) {
                    long targetChannelId = channelId;
                    channel.delete().queue(
                            success -> {
                                removeManagedPrivateChannel(guildId, targetChannelId);
                                removeCacheEntry(guildId, targetChannelId);
                            },
                            error -> {
                            }
                    );
                }
            }
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

    private void loadCacheFromRepository() {
        privateChannelsByGuild.clear();
        PRIVATE_CHANNELS.clear();
        ROOM_OWNERS.clear();
        List<PrivateRoomCacheEntry> entries;
        try {
            entries = cacheRepository.findAll();
        } catch (Exception e) {
            System.err.println("[NoRule] Failed to load private room cache: " + e.getMessage());
            return;
        }
        for (PrivateRoomCacheEntry entry : entries) {
            long guildId = entry.getGuildId();
            long channelId = entry.getChannelId();
            privateChannelsByGuild.computeIfAbsent(guildId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
            PRIVATE_CHANNELS.computeIfAbsent(guildId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
            if (entry.getOwnerId() != null) {
                ROOM_OWNERS.computeIfAbsent(guildId, id -> new ConcurrentHashMap<>()).put(channelId, entry.getOwnerId());
            }
        }
    }

    private void upsertCacheEntry(long guildId, long channelId, Long ownerId) {
        try {
            cacheRepository.upsert(new PrivateRoomCacheEntry(guildId, channelId, ownerId, System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
    }

    private void removeCacheEntry(long guildId, long channelId) {
        try {
            cacheRepository.remove(guildId, channelId);
        } catch (Exception ignored) {
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
        PRIVATE_CHANNELS.computeIfAbsent(guildId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
        PrivateRoomService listener = activeInstance;
        if (listener != null) {
            listener.privateChannelsByGuild.computeIfAbsent(guildId, id -> ConcurrentHashMap.newKeySet()).add(channelId);
            listener.upsertCacheEntry(guildId, channelId, userId);
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
