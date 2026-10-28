package com.norule.musicbot.discord.bot.gateway.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.norule.musicbot.config.domain.GatewayMonitoringConfig;
import com.norule.musicbot.service.monitoring.GatewayMonitorService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayLifecycleListenerTest {
    private final JDA jda = (JDA) Proxy.newProxyInstance(JDA.class.getClassLoader(), new Class<?>[]{JDA.class},
            (proxy, method, args) -> method.getName().equals("getResponseTotal") ? 0L : null);

    @Test
    void preservesUnmappedRawCloseCodesAndHandlesMissingFrames() {
        var monitor = new GatewayMonitorService(GatewayMonitoringConfig.defaultValues());
        var listener = new GatewayLifecycleListener(monitor);
        Logger logger = (Logger) LoggerFactory.getLogger(GatewayMonitorService.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            listener.onSessionDisconnect(new SessionDisconnectEvent(jda,
                    WebSocketFrame.createCloseFrame(4999, "custom reason"),
                    WebSocketFrame.createCloseFrame(1000, "client close"), true, OffsetDateTime.now()));
            listener.onSessionDisconnect(new SessionDisconnectEvent(jda, null, null, false, OffsetDateTime.now()));
            listener.onSessionResume(new SessionResumeEvent(jda));
            listener.onSessionRecreate(new SessionRecreateEvent(jda));
            var messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(text -> text.contains("serverCloseCode=4999 clientCloseCode=1000 reason=custom reason")));
            assertTrue(messages.stream().anyMatch(text -> text.contains("serverCloseCode=null clientCloseCode=null reason=TRANSPORT_CLOSED_WITHOUT_REASON")));
            assertTrue(messages.stream().anyMatch(text -> text.contains("result=RESUMED resumed=true")));
            assertTrue(messages.stream().anyMatch(text -> text.contains("result=RECREATED resumed=false")));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void mapsJdaTerminalCodesIntoRecoveryEligibility() {
        for (int code : new int[]{1006, 1000, 4004, 4014, -1}) {
            AtomicLong clock = new AtomicLong();
            var monitor = new GatewayMonitorService(new GatewayMonitoringConfig(60, 300, 300, true, 1800, 1800, 3, 60), clock::get);
            var listener = new GatewayLifecycleListener(monitor);
            monitor.connected("INITIAL_READY");
            listener.onShutdown(new ShutdownEvent(jda, OffsetDateTime.now(), code));
            clock.set(TimeUnit.SECONDS.toNanos(1800));
            if (code == 1006) {
                assertTrue(monitor.checkOffline("SHUTDOWN", 900));
            } else {
                assertFalse(monitor.checkOffline("SHUTDOWN", 900), "code=" + code);
            }
        }
    }
}
