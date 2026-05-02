package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.GuildSettingsService;

import java.util.function.UnaryOperator;

public final class GuildDomainConfigAdapter {
    private final GuildSettingsService settingsService;
    private volatile BotConfig.Music globalMusic;

    public GuildDomainConfigAdapter(GuildSettingsService settingsService, BotConfig.Music globalMusic) {
        this.settingsService = settingsService;
        this.globalMusic = globalMusic == null ? BotConfig.Music.defaultValues() : globalMusic;
    }

    public void replaceGlobalMusic(BotConfig.Music globalMusic) {
        this.globalMusic = globalMusic == null ? BotConfig.Music.defaultValues() : globalMusic;
    }

    public String getLanguage(long guildId) {
        return settingsService.getLanguage(guildId);
    }

    public TicketConfig getTicket(long guildId) {
        return TicketConfig.fromLegacy(settingsService.getTicket(guildId));
    }

    public MusicConfig getMusic(long guildId) {
        return MusicConfig.fromLegacy(settingsService.getMusic(guildId), globalMusic);
    }

    public int getMusicHistoryLimit(long guildId) {
        return getMusic(guildId).getHistoryLimit();
    }

    public int getMusicStatsRetentionDays(long guildId) {
        return getMusic(guildId).getStatsRetentionDays();
    }

    public int getMusicPlaylistTrackLimit(long guildId) {
        return getMusic(guildId).getPlaylistTrackLimit();
    }

    public void updateTicket(long guildId, UnaryOperator<TicketConfig> updater) {
        settingsService.updateSettings(guildId, s -> {
            TicketConfig current = TicketConfig.fromLegacy(s.getTicket());
            TicketConfig updated = updater.apply(current);
            return s.withTicket(updated.toLegacy());
        });
    }

    public GuildSettingsService settingsService() {
        return settingsService;
    }
}
