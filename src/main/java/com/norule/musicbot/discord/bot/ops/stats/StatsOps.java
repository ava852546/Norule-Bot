package com.norule.musicbot.discord.bot.ops.stats;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.app.stats.MessageStatsEventService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

public final class StatsOps {
    private static final String LEADERBOARD_SELECT_ID = "stats:leaderboard:type";
    private static final String LEADERBOARD_PAGE_BTN_PREFIX = "stats:leaderboard:page:";
    private static final String LEADERBOARD_REFRESH_BTN_PREFIX = "stats:leaderboard:refresh:";

    private final MusicCommandService owner;

    public StatsOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public boolean handleSlash(String commandName, SlashCommandInteractionEvent event) {
        if (!"stats".equals(commandName) && !"top".equals(commandName)) {
            return false;
        }
        MessageStatsEventService statsService = owner.statsEventService();
        if (statsService == null) {
            return false;
        }
        statsService.onSlashCommandInteraction(event);
        return true;
    }

    public boolean handleButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || (!id.startsWith(LEADERBOARD_PAGE_BTN_PREFIX) && !id.startsWith(LEADERBOARD_REFRESH_BTN_PREFIX))) {
            return false;
        }
        MessageStatsEventService statsService = owner.statsEventService();
        if (statsService == null) {
            return false;
        }
        statsService.onButtonInteraction(event);
        return true;
    }

    public boolean handleStringSelect(StringSelectInteractionEvent event) {
        if (!LEADERBOARD_SELECT_ID.equals(event.getComponentId())) {
            return false;
        }
        MessageStatsEventService statsService = owner.statsEventService();
        if (statsService == null) {
            return false;
        }
        statsService.onStringSelectInteraction(event);
        return true;
    }
}
