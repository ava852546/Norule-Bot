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
            monitor.sample();
            String sample = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("event=RESOURCE_SAMPLE")).findFirst().orElseThrow();
            assertTrue(sample.contains("processCpuPercent="));
            assertTrue(sample.contains("heapUsedBytes="));
            assertTrue(sample.contains("gcCollections="));
            assertTrue(sample.contains("gcPauseAvailable="));
            assertTrue(sample.contains("gcPauseMaxMillis="));
            assertTrue(sample.contains("RUNNABLE="));
            assertTrue(sample.contains("WAITING="));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
