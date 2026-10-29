package com.norule.musicbot.discord.bot.service.meta;

import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DevMusicPanelAuthorizationTest {
    @Test
    void existingDeveloperAuthorizationAppliesInDmAndGuildAndReloadRevokesAccess() {
        DevService service = new DevService(config(List.of(1L)), () -> 0);
        AtomicInteger calls = new AtomicInteger();
        service.setMusicPanelCommand(argument -> {
            assertEquals("123456789", argument);
            calls.incrementAndGet();
            // Leave the Discord reply pending: this test checks authorization and dispatch.
            return new CompletableFuture<>();
        });
        service.handleMessage(event(2, false), "&dev sendmusicpanel 123456789");
        assertEquals(0, calls.get());
        service.handleMessage(event(1, false), "&dev sendmusicpanel 123456789");
        service.handleMessage(event(1, true), "&dev sendmusicpanel 123456789");
        assertEquals(2, calls.get());
        service.reloadRuntimeConfig(config(List.of()));
        service.handleMessage(event(1, false), "&dev sendmusicpanel 123456789");
        assertEquals(2, calls.get());
    }

    private RuntimeConfigSnapshot config(List<Long> developers) {
        return new RuntimeConfigSnapshot("&", 0, "en", null, 0, developers,
                "", null, null, null, null, null, null, null, null);
    }

    private MessageReceivedEvent event(long userId, boolean guildMessage) {
        User author = proxy(User.class, Map.of("getIdLong", userId, "getId", Long.toString(userId), "getAsTag", "developer"));
        Guild guild = proxy(Guild.class, Map.of("getIdLong", 10L, "getId", "10"));
        MessageChannelUnion channel = MessageChannelUnion.class.cast(Proxy.newProxyInstance(
                MessageChannelUnion.class.getClassLoader(),
                new Class<?>[]{MessageChannelUnion.class, net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class},
                (instance, method, arguments) -> Map.of(
                "getIdLong", 20L, "getId", "20", "getType", guildMessage ? ChannelType.TEXT : ChannelType.PRIVATE,
                "getGuild", guild).get(method.getName())));
        Message message = proxy(Message.class, Map.of("getAuthor", author, "getChannel", channel,
                "getIdLong", 30L, "getGuild", guild, "isFromGuild", guildMessage,
                "getChannelType", guildMessage ? ChannelType.TEXT : ChannelType.PRIVATE));
        return new MessageReceivedEvent(proxy(JDA.class, Map.of()), 0, message);
    }

    private <T> T proxy(Class<T> type, Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, arguments) -> values.get(method.getName())));
    }
}
