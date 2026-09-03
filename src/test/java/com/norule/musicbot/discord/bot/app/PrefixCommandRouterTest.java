package com.norule.musicbot.discord.bot.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PrefixCommandRouterTest {
    private static final String BILIBILI_URL = "https://www.bilibili.com/video/BV1wZMy6DE31/";

    @Test
    void rejectsDollarPWhenConfiguredPrefixDiffers() {
        assertNull(PrefixCommandRouter.parseInvocation("$p " + BILIBILI_URL, "&&"));
    }

    @Test
    void acceptsPAsAliasWithConfiguredPrefix() {
        PrefixCommandRouter.PrefixInvocation invocation =
                PrefixCommandRouter.parseInvocation("&&P " + BILIBILI_URL, "&&");

        assertEquals("play", invocation.command());
        assertEquals(BILIBILI_URL, invocation.argument());
    }

    @Test
    void preservesExistingPrefixCommands() {
        PrefixCommandRouter.PrefixInvocation invocation =
                PrefixCommandRouter.parseInvocation("&&play song name", "&&");

        assertEquals("play", invocation.command());
        assertEquals("song name", invocation.argument());
    }

    @Test
    void acceptsDollarPrefixedMusicCommandsWhenDollarIsConfigured() {
        for (String command : new String[]{
                "join", "play", "skip", "stop", "leave", "repeat", "volume", "history", "music", "playlist"
        }) {
            PrefixCommandRouter.PrefixInvocation invocation =
                    PrefixCommandRouter.parseInvocation("$" + command + " demo", "$");

            assertEquals(command, invocation.command());
            assertEquals("demo", invocation.argument());
        }
    }

    @Test
    void doesNotTreatDollarCommandsOrMissingBoundaryAsQuickPlay() {
        assertNull(PrefixCommandRouter.parseInvocation("$playlist demo", "&&"));
        assertNull(PrefixCommandRouter.parseInvocation("$phttps://example.com/audio.mp3", "&&"));
    }
}
