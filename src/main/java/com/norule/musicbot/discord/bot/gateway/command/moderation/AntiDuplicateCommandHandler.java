package com.norule.musicbot.discord.bot.gateway.command.moderation;

import com.norule.musicbot.ModerationService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.command.settings.view.BoolTextHelper;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class AntiDuplicateCommandHandler {
    private static final String ROUTE_ACTION = CommandOptions.ACTION;
    private static final String OPTION_VALUE = CommandOptions.VALUE;

    private final ModerationService moderationService;
    private final Supplier<I18nService> i18n;
    private final BoolTextHelper boolTextHelper;

    public AntiDuplicateCommandHandler(ModerationService moderationService, Supplier<I18nService> i18n, BoolTextHelper boolTextHelper) {
        this.moderationService = moderationService;
        this.i18n = i18n;
        this.boolTextHelper = boolTextHelper;
    }

    public void handleAntiDuplicateSlash(SlashCommandInteractionEvent event, String lang) {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply(i18n.get().t(lang, "general.missing_permissions",
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
                    ? !moderationService.isDuplicateDetectionEnabled(guildId)
                    : event.getOption(OPTION_VALUE).getAsBoolean();
            if (enabled) {
                Member self = event.getGuild().getSelfMember();
                List<String> missingBotPermissions = AntiDuplicatePermissionPolicy.missingEnablePermissionNames(
                        self.hasPermission(Permission.MESSAGE_MANAGE),
                        self.hasPermission(event.getGuildChannel(), Permission.MESSAGE_MANAGE),
                        self.hasPermission(Permission.MODERATE_MEMBERS));
                if (!missingBotPermissions.isEmpty()) {
                    event.reply(i18n.get().t(lang, "anti_duplicate.missing_bot_permissions",
                                    Map.of("permissions", String.join(", ", missingBotPermissions))))
                            .setEphemeral(true).queue();
                    return;
                }
            }
            moderationService.setDuplicateDetectionEnabled(guildId, enabled);
            event.reply(i18n.get().t(lang, "anti_duplicate.result_set",
                            Map.of("status", boolTextHelper.boolText(lang, enabled))))
                    .setEphemeral(true).queue();
            return;
        }

        boolean enabled = moderationService.isDuplicateDetectionEnabled(guildId);
        event.reply(i18n.get().t(lang, "anti_duplicate.result_status",
                        Map.of("status", boolTextHelper.boolText(lang, enabled))))
                .setEphemeral(true).queue();
    }
}
