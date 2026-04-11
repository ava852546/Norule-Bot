package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

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

    public PrivateRoomListener(GuildSettingsService settingsService, I18nService i18n) {
        this.settingsService = settingsService;
        this.i18n = i18n;
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
            set.remove(voice.getIdLong());
            Set<Long> globalSet = PRIVATE_CHANNELS.get(event.getGuild().getIdLong());
            if (globalSet != null) {
                globalSet.remove(voice.getIdLong());
            }
            Map<Long, Long> owners = ROOM_OWNERS.get(event.getGuild().getIdLong());
            if (owners != null) {
                owners.remove(voice.getIdLong());
            }
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
        privateChannelsByGuild.computeIfAbsent(channel.getGuild().getIdLong(), id -> ConcurrentHashMap.newKeySet())
                .add(channel.getIdLong());
        PRIVATE_CHANNELS.computeIfAbsent(channel.getGuild().getIdLong(), id -> ConcurrentHashMap.newKeySet())
                .add(channel.getIdLong());
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


