package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

class HelpViewRenderer {
    private final MusicCommandService service;

    HelpViewRenderer(MusicCommandService service) {
        this.service = service;
    }

    EmbedBuilder helpEmbed(Guild guild, String lang, String category) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(52, 152, 219));
        eb.setTitle(service.i18nService().t(lang, "help.title"));
        String botDesc = service.runtimeConfigSnapshot().getBotDescription();
        String intro = service.i18nService().t(lang, "help.intro");
        if (botDesc != null && !botDesc.isBlank()) {
            eb.setDescription(botDesc + "\n\n" + intro);
        } else {
            eb.setDescription(intro);
        }
        eb.setFooter(guild.getName(), guild.getIconUrl());
        switch (category) {
            case CommandNames.CMD_MUSIC -> eb.addField(service.i18nService().t(lang, "help.category_music"), service.i18nService().t(lang, "help.content_music"), false);
            case "settings" -> eb.addField(service.i18nService().t(lang, "help.category_settings"), service.i18nService().t(lang, "help.content_settings"), false);
            case "moderation" -> eb.addField(service.i18nService().t(lang, "help.category_moderation"), service.i18nService().t(lang, "help.content_moderation"), false);
            case "private-room" -> eb.addField(service.i18nService().t(lang, "help.category_private_room"), service.i18nService().t(lang, "help.content_private_room"), false);
            case "ticket" -> eb.addField(service.i18nService().t(lang, "help.category_ticket"), service.i18nService().t(lang, "help.content_ticket"), false);
            case "game" -> eb.addField(service.i18nService().t(lang, "help.category_game"), service.i18nService().t(lang, "help.content_game"), false);
            default -> eb.addField(service.i18nService().t(lang, "help.category_general"), service.i18nService().t(lang, "help.content_general"), false);
        }
        eb.addField(service.i18nService().t(lang, "help.tip_title"), service.i18nService().t(lang, "help.tip_body"), false);
        return eb;
    }

    StringSelectMenu helpMenu(String lang) {
        return StringSelectMenu.create(ComponentIds.HELP_SELECT_ID)
                .setPlaceholder(service.i18nService().t(lang, "help.select_placeholder"))
                .addOptions(
                        SelectOption.of(service.i18nService().t(lang, "help.category_general"), "general"),
                        SelectOption.of(service.i18nService().t(lang, "help.category_music"), CommandNames.CMD_MUSIC),
                        SelectOption.of(service.i18nService().t(lang, "help.category_settings"), "settings"),
                        SelectOption.of(service.i18nService().t(lang, "help.category_moderation"), "moderation"),
                        SelectOption.of(service.i18nService().t(lang, "help.category_private_room"), "private-room"),
                        SelectOption.of(service.i18nService().t(lang, "help.category_ticket"), "ticket"),
                        SelectOption.of(service.i18nService().t(lang, "help.category_game"), "game")
                )
                .build();
    }

    List<Button> helpButtonsPrimary(String lang, String selectedCategory) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(categoryButton(lang, "general", selectedCategory, service.i18nService().t(lang, "help.category_general")));
        buttons.add(categoryButton(lang, CommandNames.CMD_MUSIC, selectedCategory, service.i18nService().t(lang, "help.category_music")));
        buttons.add(categoryButton(lang, "settings", selectedCategory, service.i18nService().t(lang, "help.category_settings")));
        buttons.add(categoryButton(lang, "moderation", selectedCategory, service.i18nService().t(lang, "help.category_moderation")));
        buttons.add(categoryButton(lang, "private-room", selectedCategory, service.i18nService().t(lang, "help.category_private_room")));
        return buttons;
    }

    List<Button> helpButtonsSecondary(String lang, String selectedCategory) {
        return List.of(
                categoryButton(lang, "game", selectedCategory, service.i18nService().t(lang, "help.category_game")),
                categoryButton(lang, "ticket", selectedCategory, service.i18nService().t(lang, "help.category_ticket"))
        );
    }

    private Button categoryButton(String lang, String category, String selectedCategory, String label) {
        if (category.equals(selectedCategory)) {
            return Button.success(ComponentIds.HELP_BUTTON_PREFIX + category, label).asDisabled();
        }
        return Button.secondary(ComponentIds.HELP_BUTTON_PREFIX + category, label);
    }
}
