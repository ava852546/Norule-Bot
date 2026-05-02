package com.norule.musicbot.discord.gateway;

public final class LegacyContract {
    private LegacyContract() {
    }

    public static final String PREFIX_TICKET = "ticket:";
    public static final String PREFIX_PLAY = "play:";
    public static final String PREFIX_SETTINGS = "settings:";
    public static final String PREFIX_HELP = "help:";

    public static boolean isKnownInteractivePrefix(String componentId) {
        if (componentId == null || componentId.isBlank()) {
            return false;
        }
        return componentId.startsWith(PREFIX_TICKET)
                || componentId.startsWith(PREFIX_PLAY)
                || componentId.startsWith(PREFIX_SETTINGS)
                || componentId.startsWith(PREFIX_HELP);
    }
}

