package com.norule.musicbot.domain.music;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TrackRecoveryService {
    public enum StartResult {
        STARTED,
        DISABLED,
        INVALID_CONTEXT,
        STALE,
        ALREADY_IN_PROGRESS,
        EXHAUSTED
    }

    public interface RecoveryLoadHandler {
        void loaded(Object replacement, boolean seekable);

        void failed(Throwable failure);

        default void noMatches() {
            failed(null);
        }
    }

    public interface RecoveryGateway {
        long playbackGeneration();

        boolean isActive(Object track, long expectedGeneration);

        void pause(Object track, long expectedGeneration);

        void reload(String identifier, RecoveryLoadHandler handler);

        boolean replace(Object expectedTrack,
                        Object replacement,
                        long expectedGeneration,
                        long resumePosition,
                        TrackLoadContext context);

        boolean skip(Object expectedTrack, long expectedGeneration);
    }

    public interface Listener {
        Listener NOOP = new Listener() {
        };

        default void recovering(int attempt, int maxAttempts) {
        }

        default void replacementSubmitted(int attempt) {
        }

        default void recoveryFailed(Throwable failure) {
        }

        default void exhausted(int maxAttempts) {
        }
    }

    private record RecoveryOperation(Object track, long generation, int attempt) {
    }

    private final ConcurrentMap<Long, RecoveryOperation> operations = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile int maxAttempts;
    private volatile long rewindMillis;

    public TrackRecoveryService(boolean enabled, int maxAttempts, long rewindMillis) {
        updateConfig(enabled, maxAttempts, rewindMillis);
    }

    public void updateConfig(boolean enabled, int maxAttempts, long rewindMillis) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(0, maxAttempts);
        this.rewindMillis = Math.max(0L, rewindMillis);
    }

    public StartResult recover(long guildId,
                               Object track,
                               TrackLoadContext context,
                               long position,
                               boolean seekable,
                               RecoveryGateway gateway,
                               Listener listener) {
        Objects.requireNonNull(gateway, "gateway");
        Listener callbacks = listener == null ? Listener.NOOP : listener;
        if (!enabled) {
            return StartResult.DISABLED;
        }
        if (track == null || context == null || context.resolvedIdentifier().isBlank()) {
            return StartResult.INVALID_CONTEXT;
        }
        long generation = gateway.playbackGeneration();
        if (!gateway.isActive(track, generation)) {
            return StartResult.STALE;
        }
        if (context.recoveryAttempts() >= maxAttempts) {
            if (!gateway.skip(track, generation)) return StartResult.STALE;
            callbacks.exhausted(maxAttempts);
            return StartResult.EXHAUSTED;
        }

        int attempt = context.recoveryAttempts() + 1;
        RecoveryOperation operation = new RecoveryOperation(track, generation, attempt);
        if (operations.putIfAbsent(guildId, operation) != null) {
            return StartResult.ALREADY_IN_PROGRESS;
        }
        gateway.pause(track, generation);
        callbacks.recovering(attempt, maxAttempts);
        try {
            gateway.reload(context.resolvedIdentifier(), new RecoveryLoadHandler() {
                @Override
                public void loaded(Object replacement, boolean replacementSeekable) {
                    if (!isCurrentOperation(guildId, operation)
                            || replacement == null
                            || !gateway.isActive(track, generation)) {
                        operations.remove(guildId, operation);
                        return;
                    }
                    long resumePosition = seekable && replacementSeekable
                            ? Math.max(0L, position - rewindMillis)
                            : 0L;
                    boolean replaced = gateway.replace(
                            track,
                            replacement,
                            generation,
                            resumePosition,
                            context.withRecoveryAttempt(attempt, resumePosition)
                    );
                    operations.remove(guildId, operation);
                    if (replaced) {
                        callbacks.replacementSubmitted(attempt);
                    }
                }

                @Override
                public void failed(Throwable failure) {
                    if (operations.remove(guildId, operation) && gateway.skip(track, generation)) {
                        callbacks.recoveryFailed(failure);
                    }
                }
            });
        } catch (RuntimeException failure) {
            if (operations.remove(guildId, operation) && gateway.skip(track, generation)) {
                callbacks.recoveryFailed(failure);
            }
        }
        return StartResult.STARTED;
    }

    public boolean isRecoveryInProgress(long guildId) {
        return operations.containsKey(guildId);
    }

    public void cancel(long guildId) {
        operations.remove(guildId);
    }

    private boolean isCurrentOperation(long guildId, RecoveryOperation operation) {
        return operations.get(guildId) == operation;
    }
}
