package com.norule.musicbot.discord.bot.gateway.command.report;

import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import net.dv8tion.jda.api.modals.Modal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ReportCommandHandlerTest {
    @TempDir Path directory;

    @Test
    void modalOpensDirectlyAndSlowSubmissionAcknowledgesBeforeSendingReport() throws Exception {
        for (boolean success : List.of(true, false)) {
            try (var timer = new ScheduledThreadPoolExecutor(1)) {
                var discord = new Discord();
                var i18n = I18nService.load(directory.resolve(success ? "success" : "failure"), "en");
                var handler = new ReportCommandHandler(() -> i18n, () -> 12L, timer);
                handler.handleReportSlash(new SlashCommandInteractionEvent(discord.jda, 1, discord.slash), "en");
                assertNotNull(discord.modal);
                assertEquals(0, discord.ackCount, "Opening a modal must not defer");
                handler.handleReportModal(new ModalInteractionEvent(discord.jda, 2, discord.modalInteraction), "en");
                assertEquals(1, discord.ackCount);
                assertTrue(discord.ephemeral);
                assertEquals(0, discord.reports, "Report REST must wait for ACK confirmation");
                discord.ackSuccess.accept(discord.hook);
                assertEquals(1, discord.reports);
                assertEquals(0, discord.edits);
                if (success) discord.reportSuccess.accept(null);
                else discord.reportFailure.accept(new IllegalStateException("REST failure"));
                assertEquals(1, discord.edits);
                assertEquals(i18n.t("en", success ? "report.sent" : "report.send_failed"), discord.content);
                assertEquals(1, discord.ackCount);
            }
        }
    }

    @Test
    void failedAckDoesNotSendReportOrEditReply() throws Exception {
        try (var timer = new ScheduledThreadPoolExecutor(1)) {
            var discord = new Discord();
            var i18n = I18nService.load(directory, "en");
            var handler = new ReportCommandHandler(() -> i18n, () -> 12L, timer);
            handler.handleReportSlash(new SlashCommandInteractionEvent(discord.jda, 1, discord.slash), "en");
            handler.handleReportModal(new ModalInteractionEvent(discord.jda, 2, discord.modalInteraction), "en");
            discord.ackFailure.accept(new IllegalStateException("simulated expired ACK"));
            assertEquals(0, discord.reports);
            assertEquals(0, discord.edits);
            assertEquals(1, discord.ackCount);
        }
    }

    @SuppressWarnings("unchecked")
    private static final class Discord {
        Modal modal;
        int ackCount;
        int reports;
        int edits;
        boolean ephemeral;
        String content;
        Consumer<InteractionHook> ackSuccess;
        Consumer<Throwable> ackFailure;
        Consumer<Message> reportSuccess;
        Consumer<Throwable> reportFailure;
        final long id = ((System.currentTimeMillis() - 1420070400000L) << 22)
                | (System.nanoTime() & 0x3FFFFF);
        final User user = proxy(User.class, (o, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 42L;
            case "getId" -> "42";
            case "getAsMention" -> "<@42>";
            case "getAsTag" -> "Tester";
            default -> throw new AssertionError(m.getName());
        });
        final MessageChannelUnion incoming = proxy(MessageChannelUnion.class, (o, m, a) -> 25L);
        final TextChannel target = proxy(TextChannel.class, (o, m, a) -> {
            assertEquals("sendMessageEmbeds", m.getName());
            reports++;
            return action(m.getReturnType(), (success, failure) -> {
                reportSuccess = (Consumer<Message>) success;
                reportFailure = failure;
            });
        });
        final JDA jda = proxy(JDA.class, (o, m, a) -> {
            assertEquals("getTextChannelById", m.getName());
            return target;
        });
        final InteractionHook hook = proxy(InteractionHook.class, (o, m, a) -> switch (m.getName()) {
            case "isExpired" -> false;
            case "getExpirationTimestamp" -> System.currentTimeMillis() + 900_000;
            case "editOriginal" -> {
                content = (String) a[0];
                yield action(m.getReturnType(), (success, failure) -> { edits++; success.accept(null); });
            }
            default -> throw new AssertionError("Unexpected follow-up: " + m.getName());
        });
        final SlashCommandInteraction slash = proxy(SlashCommandInteraction.class, (o, m, a) -> switch (m.getName()) {
            case "getUser" -> user;
            case "getOptions" -> List.of();
            case "getChannel" -> incoming;
            case "replyModal" -> {
                modal = (Modal) a[0];
                yield action(m.getReturnType(), (success, failure) -> {});
            }
            default -> throw new AssertionError("Unexpected slash ACK: " + m.getName());
        });
        final ModalInteraction modalInteraction = proxy(ModalInteraction.class, (o, m, a) -> switch (m.getName()) {
            case "getModalId" -> modal.getId();
            case "getUser" -> user;
            case "getIdLong" -> id;
            case "getGuild" -> null;
            case "isAcknowledged" -> false;
            case "getValues" -> List.of();
            case "deferReply" -> {
                ackCount++;
                yield action(m.getReturnType(), (success, failure) -> {
                    ackSuccess = (Consumer<InteractionHook>) success;
                    ackFailure = failure;
                });
            }
            default -> throw new AssertionError("Unexpected modal operation: " + m.getName());
        });

        private Object action(Class<?> type, java.util.function.BiConsumer<Consumer<?>, Consumer<Throwable>> queue) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (o, m, a) -> {
                if (m.getName().equals("setEphemeral")) ephemeral = (boolean) a[0];
                if (m.getName().equals("queue")) {
                    Consumer<?> success = a != null && a.length > 0 ? (Consumer<?>) a[0] : ignored -> {};
                    Consumer<Throwable> failure = a != null && a.length > 1 ? (Consumer<Throwable>) a[1] : ignored -> {};
                    queue.accept(success, failure);
                    return null;
                }
                return o;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
