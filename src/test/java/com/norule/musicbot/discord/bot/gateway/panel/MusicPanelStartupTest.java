package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;

class MusicPanelStartupTest {
    @ParameterizedTest
    @ValueSource(ints = {100, 500})
    void initializationAndRestartRegisterListenersWithoutRestOrChannelCacheValidation(int count) {
        var guilds = LongStream.rangeClosed(1, count).mapToObj(id -> (Guild) Proxy.newProxyInstance(
                Guild.class.getClassLoader(), new Class<?>[]{Guild.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getIdLong")) return id;
                    throw new AssertionError("Startup must not validate incomplete caches or use REST: " + method);
                })).toList();
        var listeners = new HashSet<Long>();
        MusicPanelController.initializeGuilds(guilds, listeners::add);
        assertEquals(count, listeners.size());
        // A restart/repeated ready does not enqueue panel recovery, deletion, creation, or PATCH.
        MusicPanelController.initializeGuilds(guilds, listeners::add);
        assertEquals(count, listeners.size());
    }
}
