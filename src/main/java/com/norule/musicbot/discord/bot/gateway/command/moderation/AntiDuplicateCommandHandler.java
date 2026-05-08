package com.norule.musicbot.discord.bot.gateway.command.moderation;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AntiDuplicateCommandHandler {
    private static final String ROUTE_ACTION = CommandOptions.ACTION;
    private static final String OPTION_VALUE = CommandOptions.VALUE;

    private final MusicCommandService owner;

    public AntiDuplicateCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handleAntiDuplicateSlash(SlashCommandInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true).queue();
            return;
        }

        String sub = event.getSubcommandName();
        if ((sub == null || sub.isBlank()) && event.getOption(ROUTE_ACTION) != null) {
            sub = event.getOption(ROUTE_ACTION).getAsString();
        }
        long guildId = event.getGuild().getIdLong();

        if ("enable".equals(sub)) {
            boolean enabled = event.getOption(OPTION_VALUE) == null
                    ? !owner.moderationService().isDuplicateDetectionEnabled(guildId)
                    : event.getOption(OPTION_VALUE).getAsBoolean();
            if (enabled) {
                Member self = event.getGuild().getSelfMember();
                List<String> missingBotPermissions = new ArrayList<>();
                if (!self.hasPermission(event.getGuildChannel(), Permission.MESSAGE_MANAGE)) {
                    missingBotPermissions.add(Permission.MESSAGE_MANAGE.getName());
                }
                if (!self.hasPermission(Permission.MODERATE_MEMBERS)) {
                    missingBotPermissions.add(Permission.MODERATE_MEMBERS.getName());
                }
                if (!missingBotPermissions.isEmpty()) {
                    event.reply(owner.i18nService().t(lang, "anti_duplicate.missing_bot_permissions",
                                    Map.of("permissions", String.join(", ", missingBotPermissions))))
                            .setEphemeral(true).queue();
                    return;
                }
            }
            owner.moderationService().setDuplicateDetectionEnabled(guildId, enabled);
            event.reply(owner.i18nService().t(lang, "anti_duplicate.result_set",
                            Map.of("status", owner.boolText(lang, enabled))))
                    .setEphemeral(true).queue();
            return;
        }

        boolean enabled = owner.moderationService().isDuplicateDetectionEnabled(guildId);
        event.reply(owner.i18nService().t(lang, "anti_duplicate.result_status",
                        Map.of("status", owner.boolText(lang, enabled))))
                .setEphemeral(true).queue();
    }
}
