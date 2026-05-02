package com.norule.musicbot.discord.gateway;

public record OpsResult(boolean handled, String message) {
    public static OpsResult handledResult() {
        return new OpsResult(true, "");
    }
}

