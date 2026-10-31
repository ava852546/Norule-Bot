package com.norule.musicbot.bootstrap;

/** Application boundary loaded with the external runtime dependencies. */
public final class BotMain {
    private BotMain() {
    }

    public static void main(String[] args) {
        new RuntimeBootstrap().run();
    }
}
