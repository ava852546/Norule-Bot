package com.norule.musicbot.service.music;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Virtual clock/executor: no real threads or network and no sleeps. */
final class ManualLoadTimer extends ScheduledThreadPoolExecutor {
    long now;
    private long sequence;
    private final PriorityQueue<Event> events = new PriorityQueue<>(Comparator
            .comparingLong((Event e) -> e.due).thenComparingLong(e -> e.sequence));
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    };

    ManualLoadTimer() { super(1); }
    @Override public void execute(Runnable command) { schedule(command, 0, TimeUnit.MILLISECONDS); }
    @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Event event = new Event(command, now + unit.toMillis(delay), sequence++);
        events.add(event);
        return event;
    }
    void advance(long millis) { now += millis; runReady(); }
    void runReady() {
        int guard = 100_000;
        while (!events.isEmpty() && events.peek().due <= now) {
            if (--guard == 0) throw new AssertionError("Unbounded scheduling loop");
            Event event = events.remove();
            event.run();
            if (!event.isCancelled()) try { event.get(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
    private final class Event extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long due;
        final long sequence;
        Event(Runnable command, long due, long sequence) { super(command, null); this.due = due; this.sequence = sequence; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(due - now, TimeUnit.MILLISECONDS); }
        @Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS)); }
    }
}
