package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.YoutubeClientType;
import dev.lavalink.youtube.clients.*;
import dev.lavalink.youtube.clients.skeleton.Client;

import java.util.Arrays;
import java.util.List;

/** The sole mapping from configuration names to youtube-source 1.18.2 implementations. */
final class YoutubeClientRegistry {
    private YoutubeClientRegistry() { }

    static Client[] create(MusicConfig.Youtube config, MusicConfig.Youtube.AuthMode authMode) {
        return create(config, authMode, YoutubeClientRegistry::createClient);
    }

    static Client[] create(MusicConfig.Youtube config, MusicConfig.Youtube.AuthMode authMode,
                           java.util.function.Function<YoutubeClientType, Client> factory) {
        List<YoutubeClientType> selected = config.isClientsConfigured()
                ? config.getClients().stream().map(YoutubeClientType::parse).toList()
                : Arrays.stream(YoutubeClientType.values())
                    .filter(type -> type != YoutubeClientType.TV || authMode == MusicConfig.Youtube.AuthMode.OAUTH)
                    .toList();
        // Validate the whole selection before constructing any client or source manager.
        if (selected.isEmpty() || selected.stream().distinct().count() != selected.size()) {
            throw new IllegalArgumentException("music.youtube.clients must be non-empty and contain no duplicates");
        }
        if (selected.contains(YoutubeClientType.TV) && authMode != MusicConfig.Youtube.AuthMode.OAUTH) {
            throw new IllegalArgumentException("music.youtube.clients: TV requires effective AuthMode OAUTH");
        }
        return selected.stream().map(factory).toArray(Client[]::new);
    }

    private static Client createClient(YoutubeClientType type) {
        return switch (type) {
            case MUSIC -> new MusicWithThumbnail();
            case TV -> new Tv();
            case WEB -> new WebWithThumbnail();
            case MWEB -> new MWebWithThumbnail();
            case WEB_EMBEDDED_PLAYER -> new WebEmbeddedWithThumbnail();
            case TVHTML5_SIMPLY -> new TvHtml5SimplyWithThumbnail();
            case ANDROID_VR -> new AndroidVrWithThumbnail();
            case ANDROID_MUSIC -> new AndroidMusicWithThumbnail();
            case IOS -> new IosWithThumbnail();
        };
    }
}
