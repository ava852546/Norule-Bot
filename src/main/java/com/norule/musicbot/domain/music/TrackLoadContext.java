package com.norule.musicbot.domain.music;

public record TrackLoadContext(
        String originalInput,
        String resolvedIdentifier,
        String sourceName,
        Long requesterId,
        String requesterName,
        int recoveryAttempts,
        long recoveryResumePosition,
        String correlationId,
        java.util.List<Throwable> failures
) {
    public TrackLoadContext(String originalInput, String resolvedIdentifier, String sourceName,
                            Long requesterId, String requesterName, int recoveryAttempts,
                            long recoveryResumePosition) {
        this(originalInput, resolvedIdentifier, sourceName, requesterId, requesterName, recoveryAttempts,
                recoveryResumePosition, java.util.UUID.randomUUID().toString(), java.util.List.of());
    }

    public TrackLoadContext(String originalInput,
                            String resolvedIdentifier,
                            String sourceName,
                            Long requesterId,
                            String requesterName,
                            int recoveryAttempts) {
        this(originalInput, resolvedIdentifier, sourceName, requesterId, requesterName, recoveryAttempts, 0L);
    }

    public TrackLoadContext {
        originalInput = originalInput == null ? "" : originalInput.trim();
        resolvedIdentifier = resolvedIdentifier == null ? "" : resolvedIdentifier.trim();
        sourceName = sourceName == null || sourceName.isBlank() ? "youtube" : sourceName.trim();
        requesterName = requesterName == null ? "" : requesterName.trim();
        recoveryAttempts = Math.max(0, recoveryAttempts);
        recoveryResumePosition = Math.max(0L, recoveryResumePosition);
        failures = failures == null ? java.util.List.of() : java.util.List.copyOf(failures);
    }

    public TrackLoadContext withRecoveryAttempt(int attempts, long resumePosition) {
        return new TrackLoadContext(
                originalInput,
                resolvedIdentifier,
                sourceName,
                requesterId,
                requesterName,
                attempts,
                resumePosition,
                correlationId,
                failures
        );
    }

    public TrackLoadContext withFailure(Throwable failure) {
        if (failure == null || failures.contains(failure)) return this;
        var history = new java.util.ArrayList<>(failures);
        history.add(failure);
        return new TrackLoadContext(originalInput, resolvedIdentifier, sourceName, requesterId, requesterName,
                recoveryAttempts, recoveryResumePosition, correlationId, history);
    }

    public TrackLoadContext resetRecovery() {
        return new TrackLoadContext(originalInput, resolvedIdentifier, sourceName, requesterId, requesterName, 0, 0L);
    }
}
