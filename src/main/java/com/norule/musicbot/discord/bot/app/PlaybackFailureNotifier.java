package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackText;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelStateStore;
import com.norule.musicbot.domain.music.MusicPlayerService;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class PlaybackFailureNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaybackFailureNotifier.class);

    private final MusicCommandService service;
    private final MusicPlayerService musicService;
    private final MusicPanelStateStore panelStateStore;
    private final MusicPlaybackText musicPlaybackText;

    private final Map<Long, Long> playbackFailureLastAt = new ConcurrentHashMap<>();
    private final Map<Long, String> playbackFailureLastSig = new ConcurrentHashMap<>();

    PlaybackFailureNotifier(MusicCommandService service,
                            MusicPlayerService musicService,
                            MusicPanelStateStore panelStateStore,
                            MusicPlaybackText musicPlaybackText) {
        this.service = service;
        this.musicService = musicService;
        this.panelStateStore = panelStateStore;
        this.musicPlaybackText = musicPlaybackText;
    }

    void reportPlaybackFailure(long guildId, MusicPlayerService.PlaybackFailure failure) {
        if (failure == null || failure.rawError() == null || failure.rawError().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sig = (failure.title() == null ? "-" : failure.title()) + "|" + failure.rawError().trim();
        Long lastAt = playbackFailureLastAt.get(guildId);
        String lastSig = playbackFailureLastSig.get(guildId);
        if (lastAt != null && now - lastAt < 8000L && sig.equals(lastSig)) {
            return;
        }
        playbackFailureLastAt.put(guildId, now);
        playbackFailureLastSig.put(guildId, sig);

        JDA currentJda = service.currentJda();
        if (currentJda == null) {
            return;
        }
        Guild guild = currentJda.getGuildById(guildId);
        if (guild == null) {
            return;
        }
        Long channelId = musicService.getLastCommandChannelId(guildId);
        if (channelId == null) {
            MusicPanelStateStore.PanelRef ref = panelStateStore.getPanelRef(guildId);
            if (ref != null) {
                channelId = ref.channelId;
            }
        }
        if (channelId == null) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null || !channel.canTalk()) {
            return;
        }
        String lang = service.lang(guildId);
        String mapped = musicPlaybackText.mapMusicLoadError(lang, failure.rawError());
        channel.sendMessage(service.i18nService().t(lang, "music.playback_failed", Map.of(
                        "title", service.safe(failure.title(), 80),
                        "error", service.safe(mapped, 180)
                )))
                .queue(null, error -> LOGGER.debug("Failed to send playback failure message", error));
    }
}
