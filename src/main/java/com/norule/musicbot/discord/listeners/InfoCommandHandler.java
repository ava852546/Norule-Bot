package com.norule.musicbot.discord.listeners;

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

final class InfoCommandHandler {
    private final MusicCommandListener owner;

    InfoCommandHandler(MusicCommandListener owner) {
        this.owner = owner;
    }

    void handleUserInfo(SlashCommandInteractionEvent event, String lang) {
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

    void handleRoleInfo(SlashCommandInteractionEvent event, String lang) {
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

    void handleServerInfo(SlashCommandInteractionEvent event, String lang) {
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
        boolean zhCn = "zh-CN".equalsIgnoreCase(lang);
        boolean zh = zhCn || "zh-TW".equalsIgnoreCase(lang);
        return switch (key) {
            case "userTitle" -> zhCn ? "使用者信息" : (zh ? "使用者資訊" : "User Information");
            case "roleTitle" -> zhCn ? "身份组信息" : (zh ? "身分組資訊" : "Role Information");
            case "serverTitle" -> zhCn ? "服务器信息" : (zh ? "伺服器資訊" : "Server Information");
            case "user" -> zhCn ? "用户" : (zh ? "使用者" : "User");
            case "role" -> zhCn ? "身份组" : (zh ? "身分組" : "Role");
            case "server" -> zhCn ? "服务器" : (zh ? "伺服器" : "Server");
            case "bot" -> "Bot";
            case "createdAt" -> zhCn ? "创建时间" : (zh ? "建立時間" : "Created");
            case "joinedAt" -> zhCn ? "加入时间" : (zh ? "加入時間" : "Joined");
            case "rolesCount" -> zhCn ? "身份组数量" : (zh ? "身分組數量" : "Role Count");
            case "highestRole" -> zhCn ? "最高身份组" : (zh ? "最高身分組" : "Highest Role");
            case "roles" -> zhCn ? "身份组" : (zh ? "身分組" : "Roles");
            case "boosting" -> zhCn ? "加成时间" : (zh ? "加成時間" : "Boosting Since");
            case "avatar" -> zhCn ? "头像" : (zh ? "頭像" : "Avatar");
            case "banner" -> zhCn ? "横幅" : (zh ? "橫幅" : "Banner");
            case "roleMissing" -> zhCn ? "请选择身份组。" : (zh ? "請選擇身分組。" : "Please choose a role.");
            case "memberCount" -> zhCn ? "成员数" : (zh ? "成員數" : "Members");
            case "color" -> zhCn ? "颜色" : (zh ? "顏色" : "Color");
            case "position" -> zhCn ? "位置" : (zh ? "位置" : "Position");
            case "managed" -> zhCn ? "系统管理" : (zh ? "系統管理" : "Managed");
            case "hoisted" -> zhCn ? "独立显示" : (zh ? "獨立顯示" : "Hoisted");
            case "mentionable" -> zhCn ? "可提及" : (zh ? "可提及" : "Mentionable");
            case "permissions" -> zhCn ? "权限" : (zh ? "權限" : "Permissions");
            case "owner" -> zhCn ? "拥有者" : (zh ? "擁有者" : "Owner");
            case "members" -> zhCn ? "成员统计" : (zh ? "成員統計" : "Member Stats");
            case "channels" -> zhCn ? "频道统计" : (zh ? "頻道統計" : "Channel Stats");
            case "serverStats" -> zhCn ? "服务器统计" : (zh ? "伺服器統計" : "Server Stats");
            case "security" -> zhCn ? "安全统计" : (zh ? "安全統計" : "Security");
            case "memberStats" -> zhCn ? "总计：{total}\n用户：{humans}\nBot：{bots}" : (zh ? "總計：{total}\n使用者：{humans}\nBot：{bots}" : "Total: {total}\nHumans: {humans}\nBots: {bots}");
            case "channelStats" -> zhCn ? "文字：{text}\n语音：{voice}\n分类：{category}\n线程：{thread}" : (zh ? "文字：{text}\n語音：{voice}\n分類：{category}\n討論串：{thread}" : "Text: {text}\nVoice: {voice}\nCategories: {category}\nThreads: {thread}");
            case "serverStatsBody" -> zhCn ? "身份组：{roles}\n加成：{boosts}\n加成等级：{boostTier}" : (zh ? "身分組：{roles}\n加成：{boosts}\n加成等級：{boostTier}" : "Roles: {roles}\nBoosts: {boosts}\nBoost Tier: {boostTier}");
            case "securityBody" -> zhCn ? "验证等级：{verification}\n双重验证要求：{mfa}\n内容过滤：{explicit}\nNSFW 等级：{nsfw}\n管理员身份组：{adminRoles}" : (zh ? "驗證等級：{verification}\n雙重驗證要求：{mfa}\n內容過濾：{explicit}\nNSFW 等級：{nsfw}\n管理員身分組：{adminRoles}" : "Verification: {verification}\nMFA Requirement: {mfa}\nExplicit Filter: {explicit}\nNSFW Level: {nsfw}\nAdmin Roles: {adminRoles}");
            case "yes" -> zhCn ? "是" : (zh ? "是" : "Yes");
            case "no" -> zhCn ? "否" : (zh ? "否" : "No");
            case "more" -> zhCn ? "还有 {count} 项" : (zh ? "還有 {count} 項" : "{count} more");
            case "none" -> zhCn ? "无" : (zh ? "無" : "None");
            default -> key;
        };
    }
}
