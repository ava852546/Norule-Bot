package com.norule.musicbot.discord.bot.gateway.command.moderation;

import net.dv8tion.jda.api.Permission;

import java.util.ArrayList;
import java.util.List;

final class AntiDuplicatePermissionPolicy {
    private AntiDuplicatePermissionPolicy() {
    }

    static List<String> missingEnablePermissionNames(boolean hasGuildManageMessages,
                                                     boolean hasCommandChannelManageMessages,
                                                     boolean hasModerateMembers) {
        List<String> missing = new ArrayList<>();
        if (!hasGuildManageMessages || !hasCommandChannelManageMessages) {
            missing.add(Permission.MESSAGE_MANAGE.getName());
        }
        if (!hasModerateMembers) {
            missing.add(Permission.MODERATE_MEMBERS.getName());
        }
        return missing;
    }
}
