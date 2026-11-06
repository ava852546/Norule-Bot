package com.norule.musicbot.domain.monitoring;

import java.time.Instant;
import java.util.Map;

public record JvmResourceSample(Instant timestamp, double processCpu, double systemCpu,
        long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed,
        long gcCollections, long gcCollectionMillis, boolean pauseAvailable,
        long pauseCount, double pauseTotalMillis, double pauseMaxMillis,
        int liveThreads, int daemonThreads, int peakThreads, Map<Thread.State, Integer> threadStates) {
    public JvmResourceSample {
        threadStates = Map.copyOf(threadStates);
    }
}
