package com.norule.musicbot.discord.bot.ops.ticket;

import com.norule.musicbot.TicketService.TicketRecord;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TicketPermission {
    public boolean has(Member member, Permission permission) {
        return member != null && member.hasPermission(permission);
    }

    public boolean canCloseTicket(Member member, TicketRecord record, List<Long> supportRoleIds) {
        if (member == null || record == null) {
            return false;
        }
        return member.getIdLong() == record.getOwnerId()
                || member.hasPermission(Permission.MANAGE_CHANNEL)
                || member.hasPermission(Permission.MANAGE_SERVER)
                || isSupportMember(member, supportRoleIds);
    }

    public boolean canManageClosedActions(Member member, List<Long> supportRoleIds) {
        if (member == null) {
            return false;
        }
        return member.hasPermission(Permission.MANAGE_CHANNEL)
                || member.hasPermission(Permission.MANAGE_SERVER)
                || isSupportMember(member, supportRoleIds);
    }

    public boolean isSupportMember(Member member, List<Long> supportRoleIds) {
        if (member == null || supportRoleIds == null || supportRoleIds.isEmpty()) {
            return false;
        }
        Set<Long> roleIds = new HashSet<>();
        for (Role role : member.getRoles()) {
            roleIds.add(role.getIdLong());
        }
        for (Long roleId : supportRoleIds) {
            if (roleIds.contains(roleId)) {
                return true;
            }
        }
        return false;
    }
}
