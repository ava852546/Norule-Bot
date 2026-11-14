package com.norule.musicbot.domain.music;

import java.util.Arrays;
import java.util.Locale;

/** Names supported by the pinned youtube-source version; no authentication is enabled here. */
public enum YoutubeClientType {
    MUSIC, TV, WEB, MWEB, WEB_EMBEDDED_PLAYER, TVHTML5_SIMPLY, ANDROID_VR, ANDROID_MUSIC, IOS;

    public static YoutubeClientType parse(String name) {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            // Do not echo arbitrary configuration values, which may contain credentials.
            throw new IllegalArgumentException("music.youtube.clients contains an unsupported name; supported: "
                    + Arrays.toString(values()));
        }
    }
}
