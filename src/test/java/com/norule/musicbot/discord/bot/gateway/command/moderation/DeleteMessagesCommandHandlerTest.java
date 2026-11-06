package com.norule.musicbot.discord.bot.gateway.command.moderation;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DeleteMessagesCommandHandlerTest {
    @Test
    void checksRequesterForExplicitAndAllChannelExecutionAndRevokedPermissions() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(false);
        AtomicBoolean visible = new AtomicBoolean(true);
        AtomicInteger memberLookups = new AtomicInteger();
        Member requester = proxy(Member.class, (method, args) -> switch (method.getName()) {
            case "hasAccess" -> visible.get();
            case "hasPermission" -> allowed.get();
            default -> throw new AssertionError(method);
        });
        Member bot = proxy(net.dv8tion.jda.api.entities.SelfMember.class, (method, args) -> true);
        TextChannel[] channel = new TextChannel[1];
        Guild guild = proxy(Guild.class, (method, args) -> switch (method.getName()) {
            case "getSelfMember" -> bot;
            case "getTextChannelById" -> channel[0];
            case "getTextChannels" -> List.of(channel[0]);
            case "retrieveMemberById" -> {
                memberLookups.incrementAndGet();
                yield proxy(method.getReturnType(), (action, values) -> {
                    if (action.getName().equals("complete")) return requester;
                    throw new AssertionError(action);
                });
            }
            default -> throw new AssertionError(method);
        });
        channel[0] = proxy(TextChannel.class, (method, args) -> {
            if (method.getName().equals("getGuild")) return guild;
            // Any history read or deletion in a forbidden channel fails the test.
            throw new AssertionError(method);
        });
        DeleteMessagesCommandHandler handler = new DeleteMessagesCommandHandler(null);
        var permission = DeleteMessagesCommandHandler.class.getDeclaredMethod("canManageMessages", TextChannel.class, Member.class);
        permission.setAccessible(true);
        assertFalse((boolean) permission.invoke(handler, channel[0], requester));
        allowed.set(true);
        assertTrue((boolean) permission.invoke(handler, channel[0], requester));
        visible.set(false);
        assertFalse((boolean) permission.invoke(handler, channel[0], requester));
        visible.set(true);
        allowed.set(false);

        Class<?> requestType = Class.forName(DeleteMessagesCommandHandler.class.getName() + "$DeleteRequest");
        var constructor = requestType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var execute = DeleteMessagesCommandHandler.class.getDeclaredMethod("performDeleteRequest", Guild.class, requestType);
        execute.setAccessible(true);
        for (Long channelId : new Long[]{123L, null}) {
            Object request = constructor.newInstance(1L, channelId, 2L, Duration.ofHours(1), 10, Instant.now().plusSeconds(60));
            assertEquals(0, execute.invoke(handler, guild, request));
        }
        assertEquals(2, memberLookups.get());
    }

    private interface Call {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> call.invoke(method, args)));
    }
}
