package com.norule.musicbot.discord.bot.gateway.command.meta;

import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PingCommandHandlerTest {
    @TempDir Path directory;

    @Test
    @SuppressWarnings("unchecked")
    void failedAckNeverAttemptsAnotherReplyOrQueriesGateway() throws Exception {
        try (var scheduler = new ScheduledThreadPoolExecutor(1)) {
            var i18n = I18nService.load(directory, "en");
            var handler = new PingCommandHandler(() -> i18n, scheduler);
            var acks = new AtomicInteger();
            var submissions = new AtomicInteger();
            var action = proxy(ReplyCallbackAction.class, (o, method, args) -> {
                if (method.getName().equals("setEphemeral")) assertFalse((boolean) args[0]);
                if (method.getName().equals("queue")) {
                    submissions.incrementAndGet();
                    ((Consumer<Throwable>) args[1]).accept(new TimeoutException());
                    return null;
                }
                return o;
            });
            long id = (System.currentTimeMillis() - 1420070400000L) << 22;
            var interaction = proxy(SlashCommandInteraction.class, (o, method, args) -> switch (method.getName()) {
                case "getIdLong" -> id;
                case "getGuild" -> null;
                case "isAcknowledged" -> false;
                case "deferReply" -> { acks.incrementAndGet(); yield action; }
                default -> throw new AssertionError("Unexpected reply after ACK failure: " + method.getName());
            });
            var jda = proxy(JDA.class, (o, method, args) -> { throw new AssertionError("Backend ran before ACK"); });
            handler.handlePingSlash(new SlashCommandInteractionEvent(jda, 1, interaction), "en");
            assertEquals(1, acks.get());
            assertEquals(1, submissions.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
