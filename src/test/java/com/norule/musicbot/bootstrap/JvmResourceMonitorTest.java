package com.norule.musicbot.bootstrap;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmResourceMonitorTest {
    @Test
    void samplesRealJvmResourcesAndClosesRecording() {
        Logger logger = (Logger) LoggerFactory.getLogger(JvmResourceMonitor.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try (JvmResourceMonitor monitor = new JvmResourceMonitor()) {
            var sample = monitor.sample();
            assertTrue(sample.heapUsed() > 0);
            assertTrue(sample.heapCommitted() >= sample.heapUsed());
            assertTrue(sample.gcCollections() >= 0);
            assertTrue(sample.liveThreads() > 0);
            assertTrue(sample.threadStates().containsKey(Thread.State.RUNNABLE));
            assertTrue(sample.threadStates().containsKey(Thread.State.WAITING));
            assertTrue(monitor.sample().gcCollections() >= 0);
            assertTrue(logs.list.stream().noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
