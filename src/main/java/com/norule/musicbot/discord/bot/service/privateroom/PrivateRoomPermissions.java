package com.norule.musicbot.discord.bot.service.privateroom;

import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared by settings validation and the runtime create boundary. */
public final class PrivateRoomPermissions {
    private PrivateRoomPermissions() { }

    public record Check(long guildId, Long channelId, Long categoryId, boolean triggerAvailable, List<Permission> missing) {
        public boolean allowed() { return triggerAvailable && missing.isEmpty(); }
        public String message(I18nService i18n, String language) {
            String permissions = missing.stream().map(permission -> i18n.t(language,
                    "room_settings.permission_" + permission.name().toLowerCase(java.util.Locale.ROOT)))
                    .collect(java.util.stream.Collectors.joining(", "));
            return i18n.t(language, triggerAvailable ? "room_settings.enable_missing_permissions" : "room_settings.trigger_unavailable",
                    Map.of("permissions", permissions, "guild", String.valueOf(guildId),
                            "channel", String.valueOf(channelId), "category", String.valueOf(categoryId)));
        }
    }

    public static Check check(Guild guild, Long triggerId) {
        VoiceChannel trigger = triggerId == null ? null : guild.getVoiceChannelById(triggerId);
        if (trigger == null) return new Check(guild.getIdLong(), triggerId, null, false, List.of());
        var self = guild.getSelfMember();
        var category = trigger.getParentCategory();
        List<Permission> missing = new ArrayList<>();
        // createVoiceChannel is a Guild operation; inherited overrides are checked separately.
        if (!self.hasPermission(Permission.MANAGE_CHANNEL) || !hasTargetPermission(self, category, Permission.MANAGE_CHANNEL))
            missing.add(Permission.MANAGE_CHANNEL);
        if (!hasTargetPermission(self, category, Permission.MANAGE_PERMISSIONS)) missing.add(Permission.MANAGE_PERMISSIONS);
        if (!self.hasPermission(trigger, Permission.VOICE_MOVE_OTHERS) || !hasTargetPermission(self, category, Permission.VOICE_MOVE_OTHERS))
            missing.add(Permission.VOICE_MOVE_OTHERS);
        if (!self.hasPermission(trigger, Permission.VIEW_CHANNEL) || !hasTargetPermission(self, category, Permission.VIEW_CHANNEL))
            missing.add(Permission.VIEW_CHANNEL);
        if (!self.hasPermission(trigger, Permission.VOICE_CONNECT) || !hasTargetPermission(self, category, Permission.VOICE_CONNECT))
            missing.add(Permission.VOICE_CONNECT);
        return new Check(guild.getIdLong(), triggerId, category == null ? null : category.getIdLong(), true, List.copyOf(missing));
    }

    private static boolean hasTargetPermission(net.dv8tion.jda.api.entities.Member member, GuildChannel category, Permission permission) {
        return category == null ? member.hasPermission(permission) : member.hasPermission(category, permission);
    }
}
