package com.norule.musicbot.domain.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackSchedulerRecoveryTest {
    @Test
    void stateListenerReceivesSpecificPlaybackReasons() {
        FakePlayer player = new FakePlayer();
        TrackScheduler scheduler = new TrackScheduler(player.proxy);
        var reasons = new java.util.ArrayList<MusicStateChange>();
        scheduler.setStateChangeListener(reasons::add);
        FakeTrack track = new FakeTrack("track", true);
        scheduler.queue(track.proxy);
        scheduler.onTrackStart(player.proxy, track.proxy);
        scheduler.setRepeatMode("ALL");
        scheduler.onTrackEnd(player.proxy, track.proxy, AudioTrackEndReason.STOPPED);
        assertEquals(java.util.List.of(MusicStateChange.QUEUE_CHANGED, MusicStateChange.TRACK_START,
                MusicStateChange.LOOP_CHANGED, MusicStateChange.TRACK_END), reasons);
    }

    @Test
    void successfulRecoveryNotifiesWithoutChangingTheRecoveryContract() {
        FakePlayer player = new FakePlayer();
        TrackScheduler scheduler = new TrackScheduler(player.proxy);
        FakeTrack old = new FakeTrack("old", true);
        FakeTrack replacement = new FakeTrack("replacement", true);
        scheduler.queue(old.proxy);
        var reasons = new java.util.ArrayList<MusicStateChange>();
        scheduler.setStateChangeListener(reasons::add);
        assertTrue(scheduler.replaceIfCurrent(old.proxy, replacement.proxy,
                scheduler.getPlaybackGeneration(), 28_000L));
        assertEquals(java.util.List.of(MusicStateChange.RECOVERY), reasons);
        assertEquals(28_000L, replacement.position);
    }

    @Test
    void loadFailedEndEventDoesNotAdvanceQueueDuringRecovery() {
        FakePlayer player = new FakePlayer();
        TrackScheduler scheduler = new TrackScheduler(player.proxy);
        FakeTrack old = new FakeTrack("old", true);
        FakeTrack queued = new FakeTrack("queued", true);
        FakeTrack replacement = new FakeTrack("replacement", true);

        scheduler.queue(old.proxy);
        scheduler.queue(queued.proxy);
        long generation = scheduler.getPlaybackGeneration();
        scheduler.pauseIfCurrent(old.proxy, generation);

        player.current = null;
        scheduler.onTrackEnd(player.proxy, old.proxy, AudioTrackEndReason.LOAD_FAILED);

        assertEquals(1, scheduler.snapshotQueue().size());
        assertTrue(scheduler.isActiveTrack(old.proxy, generation));
        assertTrue(scheduler.replaceIfCurrent(old.proxy, replacement.proxy, generation, 28_000L));
        assertSame(replacement.proxy, player.current);
        assertEquals(28_000L, replacement.position);
    }

    @Test
    void manualSkipInvalidatesPendingReplacement() {
        FakePlayer player = new FakePlayer();
        TrackScheduler scheduler = new TrackScheduler(player.proxy);
        FakeTrack old = new FakeTrack("old", true);
        FakeTrack queued = new FakeTrack("queued", true);
        FakeTrack replacement = new FakeTrack("replacement", true);

        scheduler.queue(old.proxy);
        scheduler.queue(queued.proxy);
        long generation = scheduler.getPlaybackGeneration();
        scheduler.pauseIfCurrent(old.proxy, generation);
        scheduler.nextTrack();

        assertSame(queued.proxy, player.current);
        assertFalse(scheduler.replaceIfCurrent(old.proxy, replacement.proxy, generation, 10_000L));
        assertSame(queued.proxy, player.current);
    }

    @Test
    void exceptionSkipAndLoadFailedEndEventAdvanceOnlyOnce() {
        FakePlayer player = new FakePlayer();
        TrackScheduler scheduler = new TrackScheduler(player.proxy);
        FakeTrack failed = new FakeTrack("failed", true);
        FakeTrack next = new FakeTrack("next", true);
        FakeTrack afterNext = new FakeTrack("after-next", true);

        scheduler.queue(failed.proxy);
        scheduler.queue(next.proxy);
        scheduler.queue(afterNext.proxy);
        scheduler.setTrackExceptionListener((track, exception) ->
                scheduler.skipIfCurrent(track, scheduler.getPlaybackGeneration()));

        scheduler.onTrackException(
                player.proxy,
                failed.proxy,
                new FriendlyException("playback failed", FriendlyException.Severity.SUSPICIOUS, null)
        );
        scheduler.onTrackEnd(player.proxy, failed.proxy, AudioTrackEndReason.LOAD_FAILED);

        assertSame(next.proxy, player.current);
        assertEquals(1, scheduler.snapshotQueue().size());
        assertSame(afterNext.proxy, scheduler.snapshotQueue().get(0));
    }

    private static final class FakePlayer {
        private AudioTrack current;
        private boolean paused;
        private final AudioPlayer proxy = (AudioPlayer) Proxy.newProxyInstance(
                AudioPlayer.class.getClassLoader(),
                new Class<?>[] {AudioPlayer.class},
                (ignored, method, args) -> switch (method.getName()) {
                    case "startTrack" -> startTrack((AudioTrack) args[0], (boolean) args[1]);
                    case "getPlayingTrack" -> current;
                    case "setPaused" -> {
                        paused = (boolean) args[0];
                        yield null;
                    }
                    case "isPaused" -> paused;
                    default -> defaultValue(method.getReturnType());
                }
        );

        private boolean startTrack(AudioTrack track, boolean noInterrupt) {
            if (noInterrupt && current != null) {
                return false;
            }
            current = track;
            return true;
        }
    }

    private static final class FakeTrack {
        private final String identifier;
        private final boolean seekable;
        private long position;
        private Object userData;
        private final AudioTrack proxy;

        private FakeTrack(String identifier, boolean seekable) {
            this.identifier = identifier;
            this.seekable = seekable;
            this.proxy = (AudioTrack) Proxy.newProxyInstance(
                    AudioTrack.class.getClassLoader(),
                    new Class<?>[] {AudioTrack.class},
                    (ignored, method, args) -> switch (method.getName()) {
                        case "getIdentifier" -> this.identifier;
                        case "isSeekable" -> this.seekable;
                        case "getPosition" -> this.position;
                        case "setPosition" -> {
                            this.position = (long) args[0];
                            yield null;
                        }
                        case "getUserData" -> this.userData;
                        case "setUserData" -> {
                            this.userData = args[0];
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
