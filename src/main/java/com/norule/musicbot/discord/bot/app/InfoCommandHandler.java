package com.norule.musicbot.discord.bot.app;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.awt.Color;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class InfoCommandHandler {
    private final MusicCommandService owner;

    InfoCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handleUserInfo(SlashCommandInteractionEvent event, String lang) {
        Member target = resolveTargetMember(event);
        if (target == null) {
            event.reply(owner.i18nService().t(lang, "general.invalid_user")).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue(hook -> target.getUser().retrieveProfile().queue(
                profile -> hook.sendMessageEmbeds(userEmbed(target, lang, profile.getBannerUrl()).build()).queue(),
                error -> hook.sendMessageEmbeds(userEmbed(target, lang, null).build()).queue()
        ));
    }

    public void handleRoleInfo(SlashCommandInteractionEvent event, String lang) {
        OptionMapping option = event.getOption("role");
        Role role = option == null ? null : option.getAsRole();
        if (role == null) {
            event.reply(text(lang, "roleMissing")).setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue(hook -> {
            if (role.isPublicRole()) {
                hook.sendMessageEmbeds(roleEmbed(role, lang, role.getGuild().getMemberCount()).build()).queue();
                return;
            }
            role.getGuild().findMembersWithRoles(role)
                    .setTimeout(Duration.ofSeconds(20))
                    .onSuccess(members -> hook.sendMessageEmbeds(roleEmbed(role, lang, members.size()).build()).queue())
                    .onError(error -> hook.sendMessageEmbeds(roleEmbed(role, lang, role.getGuild().getMembersWithRoles(role).size()).build()).queue());
        });
    }

    public void handleServerInfo(SlashCommandInteractionEvent event, String lang) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply(owner.i18nService().t(lang, "general.only_guild")).setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue(hook -> guild.findMembers(member -> true)
                .setTimeout(Duration.ofSeconds(20))
                .onSuccess(members -> hook.sendMessageEmbeds(serverEmbed(guild, lang, members).build()).queue())
                .onError(error -> hook.sendMessageEmbeds(serverEmbed(guild, lang, guild.getMembers()).build()).queue()));
    }

    private Member resolveTargetMember(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption("user");
        if (option != null) {
            Member member = option.getAsMember();
            if (member != null) {
                return member;
            }
            User user = option.getAsUser();
            return event.getGuild() == null ? null : event.getGuild().getMember(user);
        }
        return event.getMember();
    }

    @SuppressWarnings("deprecation")
    private EmbedBuilder userEmbed(Member member, String lang, String bannerUrl) {
        User user = member.getUser();
        List<Role> roles = member.getRoles();
        String roleText = roles.isEmpty()
                ? text(lang, "none")
                : roles.stream()
                .sorted(Comparator.comparingInt(Role::getPositionRaw).reversed())
                .limit(12)
                .map(Role::getAsMention)
                .collect(Collectors.joining(" "));
        if (roles.size() > 12) {
            roleText += "\n" + text(lang, "more", Map.of("count", String.valueOf(roles.size() - 12)));
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(member.getColor() == null ? new Color(88, 101, 242) : member.getColor())
                .setTitle(text(lang, "userTitle"))
                .setThumbnail(user.getEffectiveAvatarUrl())
                .addField(text(lang, "user"), user.getAsMention() + "\n`" + user.getName() + "`", true)
                .addField("ID", user.getId(), true)
                .addField(text(lang, "bot"), yesNo(lang, user.isBot()), true)
                .addField(text(lang, "createdAt"), timestamp(user.getTimeCreated()), true)
                .addField(text(lang, "joinedAt"), timestamp(member.getTimeJoined()), true)
                .addField(text(lang, "rolesCount"), String.valueOf(roles.size()), true)
                .addField(text(lang, "highestRole"), roles.isEmpty() ? text(lang, "none") : roles.get(0).getAsMention(), true)
                .addField(text(lang, "boosting"), member.getTimeBoosted() == null ? text(lang, "none") : timestamp(member.getTimeBoosted()), true)
                .addField(text(lang, "avatar"), "[Link](" + user.getEffectiveAvatarUrl() + ")", true)
                .addField(text(lang, "roles"), roleText, false);
        if (bannerUrl != null && !bannerUrl.isBlank()) {
            embed.addField(text(lang, "banner"), "[Link](" + bannerUrl + ")", true);
            embed.setImage(bannerUrl);
        }
        return embed.setFooter(member.getGuild().getName(), member.getGuild().getIconUrl());
    }

    @SuppressWarnings("deprecation")
    private EmbedBuilder roleEmbed(Role role, String lang, int memberCount) {
        Guild guild = role.getGuild();
        Color color = role.getColor() == null ? new Color(88, 101, 242) : role.getColor();
        String colorText = role.getColor() == null ? text(lang, "none") : String.format("#%06X", role.getColorRaw() & 0xFFFFFF);
        return new EmbedBuilder()
                .setColor(color)
                .setTitle(text(lang, "roleTitle"))
                .addField(text(lang, "role"), role.getAsMention() + "\n`" + role.getName() + "`", true)
                .addField("ID", role.getId(), true)
                .addField(text(lang, "memberCount"), String.valueOf(memberCount), true)
                .addField(text(lang, "color"), colorText, true)
                .addField(text(lang, "position"), String.valueOf(role.getPosition()), true)
                .addField(text(lang, "createdAt"), timestamp(role.getTimeCreated()), true)
                .addField(text(lang, "managed"), yesNo(lang, role.isManaged()), true)
                .addField(text(lang, "hoisted"), yesNo(lang, role.isHoisted()), true)
                .addField(text(lang, "mentionable"), yesNo(lang, role.isMentionable()), true)
                .addField(text(lang, "permissions"), permissions(role), false)
                .setFooter(guild.getName(), guild.getIconUrl());
    }

    private EmbedBuilder serverEmbed(Guild guild, String lang, List<Member> members) {
        long textChannels = guild.getChannelCache().stream().filter(ch -> ch.getType() == ChannelType.TEXT).count();
        long voiceChannels = guild.getChannelCache().stream().filter(ch -> ch.getType() == ChannelType.VOICE).count();
        long categories = guild.getChannelCache().stream().filter(ch -> ch.getType() == ChannelType.CATEGORY).count();
        long threads = guild.getChannelCache().stream().filter(ch -> ch.getType().isThread()).count();
        long bots = members.stream().filter(member -> member.getUser().isBot()).count();
        long humans = Math.max(0, guild.getMemberCount() - bots);
        long adminRoles = guild.getRoles().stream()
                .filter(role -> !role.isPublicRole() && role.hasPermission(Permission.ADMINISTRATOR))
                .count();

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle(text(lang, "serverTitle"))
                .setThumbnail(guild.getIconUrl())
                .addField(text(lang, "server"), guild.getName() + "\n`" + guild.getId() + "`", true)
                .addField(text(lang, "owner"), guild.getOwner() == null ? "<@" + guild.getOwnerId() + ">" : guild.getOwner().getAsMention(), true)
                .addField(text(lang, "createdAt"), timestamp(guild.getTimeCreated()), true)
                .addField(text(lang, "members"), text(lang, "memberStats", Map.of(
                        "total", String.valueOf(guild.getMemberCount()),
                        "humans", String.valueOf(humans),
                        "bots", String.valueOf(bots)
                )), false)
                .addField(text(lang, "channels"), text(lang, "channelStats", Map.of(
                        "text", String.valueOf(textChannels),
                        "voice", String.valueOf(voiceChannels),
                        "category", String.valueOf(categories),
                        "thread", String.valueOf(threads)
                )), false)
                .addField(text(lang, "serverStats"), text(lang, "serverStatsBody", Map.of(
                        "roles", String.valueOf(guild.getRoles().size()),
                        "boosts", String.valueOf(guild.getBoostCount()),
                        "boostTier", guild.getBoostTier().name()
                )), false)
                .addField(text(lang, "security"), text(lang, "securityBody", Map.of(
                        "verification", guild.getVerificationLevel().name(),
                        "mfa", guild.getRequiredMFALevel().name(),
                        "explicit", guild.getExplicitContentLevel().name(),
                        "nsfw", guild.getNSFWLevel().name(),
                        "adminRoles", String.valueOf(adminRoles)
                )), false);
        if (guild.getBannerUrl() != null) {
            embed.setImage(guild.getBannerUrl());
        }
        return embed.setFooter(guild.getName(), guild.getIconUrl());
    }

    private String permissions(Role role) {
        EnumSet<Permission> permissions = Permission.getPermissions(role.getPermissionsRaw());
        if (permissions.isEmpty()) {
            return text(role.getGuild().getIdLong(), "none");
        }
        List<String> names = permissions.stream()
                .filter(permission -> permission != Permission.UNKNOWN)
                .map(Permission::getName)
                .limit(18)
                .toList();
        String joined = String.join(", ", names);
        int extra = Math.max(0, permissions.size() - names.size());
        return extra == 0 ? joined : joined + "\n" + text(role.getGuild().getIdLong(), "more", Map.of("count", String.valueOf(extra)));
    }

    private String timestamp(OffsetDateTime time) {
        if (time == null) {
            return "-";
        }
        long epoch = time.toEpochSecond();
        return "<t:" + epoch + ":F>\n<t:" + epoch + ":R>";
    }

    private String yesNo(String lang, boolean value) {
        return text(lang, value ? "yes" : "no");
    }

    private String text(long guildId, String key) {
        return text(owner.lang(guildId), key);
    }

    private String text(String lang, String key) {
        return text(lang, key, Map.of());
    }

    private String text(long guildId, String key, Map<String, String> placeholders) {
        return text(owner.lang(guildId), key, placeholders);
    }

    private String text(String lang, String key, Map<String, String> placeholders) {
        String value = rawText(lang, key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    private String rawText(String lang, String key) {
        return switch (key) {
            case "userTitle" -> "User Information";
            case "roleTitle" -> "Role Information";
            case "serverTitle" -> "Server Information";
            case "user" -> "User";
            case "role" -> "Role";
            case "server" -> "Server";
            case "bot" -> "Bot";
            case "createdAt" -> "Created";
            case "joinedAt" -> "Joined";
            case "rolesCount" -> "Role Count";
            case "highestRole" -> "Highest Role";
            case "roles" -> "Roles";
            case "boosting" -> "Boosting Since";
            case "avatar" -> "Avatar";
            case "banner" -> "Banner";
            case "roleMissing" -> "Please choose a role.";
            case "memberCount" -> "Members";
            case "color" -> "Color";
            case "position" -> "Position";
            case "managed" -> "Managed";
            case "hoisted" -> "Hoisted";
            case "mentionable" -> "Mentionable";
            case "permissions" -> "Permissions";
            case "owner" -> "Owner";
            case "members" -> "Member Stats";
            case "channels" -> "Channel Stats";
            case "serverStats" -> "Server Stats";
            case "security" -> "Security";
            case "memberStats" -> "Total: {total}\nHumans: {humans}\nBots: {bots}";
            case "channelStats" -> "Text: {text}\nVoice: {voice}\nCategories: {category}\nThreads: {thread}";
            case "serverStatsBody" -> "Roles: {roles}\nBoosts: {boosts}\nBoost Tier: {boostTier}";
            case "securityBody" -> "Verification: {verification}\nMFA Requirement: {mfa}\nExplicit Filter: {explicit}\nNSFW Level: {nsfw}\nAdmin Roles: {adminRoles}";
            case "yes" -> "Yes";
            case "no" -> "No";
            case "more" -> "{count} more";
            case "none" -> "None";
            default -> key;
        };
    }
}
