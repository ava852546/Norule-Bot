package com.norule.musicbot.discord.bot.gateway.command.moderation;

import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiDuplicatePermissionPolicyTest {

    @Test
    void enablingRequiresGuildManageMessagesEvenWhenCommandChannelAllowsIt() {
        List<String> missing = AntiDuplicatePermissionPolicy.missingEnablePermissionNames(false, true, true);

        assertEquals(List.of(Permission.MESSAGE_MANAGE.getName()), missing);
    }

    @Test
    void enablingRequiresCommandChannelManageMessages() {
        List<String> missing = AntiDuplicatePermissionPolicy.missingEnablePermissionNames(true, false, true);

        assertEquals(List.of(Permission.MESSAGE_MANAGE.getName()), missing);
    }

    @Test
    void enablingRequiresModerateMembers() {
        List<String> missing = AntiDuplicatePermissionPolicy.missingEnablePermissionNames(true, true, false);

        assertEquals(List.of(Permission.MODERATE_MEMBERS.getName()), missing);
    }

    @Test
    void enablingPassesWhenAllRequiredBotPermissionsAreAvailable() {
        List<String> missing = AntiDuplicatePermissionPolicy.missingEnablePermissionNames(true, true, true);

        assertTrue(missing.isEmpty());
    }
}
