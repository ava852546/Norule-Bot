package com.norule.musicbot.bootstrap;

import com.sun.management.OperatingSystemMXBean;
import jdk.jfr.consumer.RecordingStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.EnumMap;

/** Uses JDK instrumentation only; JFR GCPhasePause measures stop-the-world pauses. */
final class JvmResourceMonitor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(JvmResourceMonitor.class);
    private long pauseNanos;
    private long maxPauseNanos;
    private long pauseCount;
    private final RecordingStream gcRecording;
    private volatile boolean pauseRecordingAvailable;
    private long previousCollections;
    private long previousCollectionMillis;

    JvmResourceMonitor() {
        gcRecording = startPauseRecording();
        previousCollections = collectionCount();
        previousCollectionMillis = collectionMillis();
    }

    private RecordingStream startPauseRecording() {
        RecordingStream recording = null;
        try {
            recording = new RecordingStream();
            recording.setMaxAge(Duration.ofMinutes(2));
            recording.setMaxSize(32L * 1024 * 1024);
            recording.enable("jdk.GCPhasePause").withoutStackTrace();
            recording.onEvent("jdk.GCPhasePause", event -> recordPause(event.getDuration().toNanos()));
            recording.onError(error -> {
                pauseRecordingAvailable = false;
                LOGGER.warn("[JVM] GC pause recording failed; MXBean collection counters remain available", error);
            });
            recording.onClose(() -> pauseRecordingAvailable = false);
            pauseRecordingAvailable = true;
            recording.startAsync();
            return recording;
        } catch (RuntimeException | LinkageError failure) {
            if (recording != null) {
                recording.close();
            }
            pauseRecordingAvailable = false;
            LOGGER.warn("[JVM] JFR unavailable; GC pause values will be unavailable", failure);
            return null;
        }
    }

    void sample() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        var os = ManagementFactory.getOperatingSystemMXBean();
        double processCpu = -1;
        double systemCpu = -1;
        if (os instanceof OperatingSystemMXBean extended) {
            processCpu = extended.getProcessCpuLoad();
            systemCpu = extended.getCpuLoad();
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        EnumMap<Thread.State, Integer> states = new EnumMap<>(Thread.State.class);
        for (Thread.State state : Thread.State.values()) {
            states.put(state, 0);
        }
        for (ThreadInfo thread : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (thread != null) {
                states.merge(thread.getThreadState(), 1, Integer::sum);
            }
        }
        long collections = collectionCount();
        long collectionMillis = collectionMillis();
        PauseSample pauses = drainPauses();
        LOGGER.info("[JVM] event=RESOURCE_SAMPLE processCpuPercent={} systemCpuPercent={} heapUsedBytes={} heapCommittedBytes={} heapMaxBytes={} nonHeapUsedBytes={} gcCollections={} gcCollectionMillis={} gcPauseAvailable={} gcPauseCount={} gcPauseTotalMillis={} gcPauseMaxMillis={} liveThreads={} daemonThreads={} peakThreads={} threadStates={}",
                percent(processCpu), percent(systemCpu), heap.getUsed(), heap.getCommitted(), heap.getMax(),
                nonHeap.getUsed(), Math.max(0, collections - previousCollections),
                Math.max(0, collectionMillis - previousCollectionMillis), pauseRecordingAvailable,
                pauses.count(), pauseRecordingAvailable ? pauses.totalNanos() / 1_000_000.0 : "UNAVAILABLE",
                pauseRecordingAvailable ? pauses.maxNanos() / 1_000_000.0 : "UNAVAILABLE",
                threads.getThreadCount(), threads.getDaemonThreadCount(), threads.getPeakThreadCount(), states);
        previousCollections = collections;
        previousCollectionMillis = collectionMillis;
    }

    private synchronized void recordPause(long duration) {
        pauseNanos += duration;
        maxPauseNanos = Math.max(maxPauseNanos, duration);
        pauseCount++;
    }

    private synchronized PauseSample drainPauses() {
        PauseSample sample = new PauseSample(pauseCount, pauseNanos, maxPauseNanos);
        pauseCount = 0;
        pauseNanos = 0;
        maxPauseNanos = 0;
        return sample;
    }

    private record PauseSample(long count, long totalNanos, long maxNanos) {}

    private static Object percent(double load) {
        return load < 0 ? "UNAVAILABLE" : Math.round(load * 10_000.0) / 100.0;
    }

    private static long collectionCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static long collectionMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    @Override
    public void close() {
        if (gcRecording != null) {
            gcRecording.close();
        }
    }
}
