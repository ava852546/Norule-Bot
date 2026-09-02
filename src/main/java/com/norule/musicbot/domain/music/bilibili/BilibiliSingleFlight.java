package com.norule.musicbot.domain.music.bilibili;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BilibiliSingleFlight<T> {
    private final ConcurrentHashMap<String, CompletableFuture<T>> inFlight = new ConcurrentHashMap<>();
    private final AtomicInteger activeParticipants = new AtomicInteger();

    public T execute(String key, Duration timeout, ThrowingSupplier<T> supplier) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(supplier, "supplier");
        Duration boundedTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
                : timeout;
        activeParticipants.incrementAndGet();
        try {
            return executeInternal(key, boundedTimeout, supplier);
        } finally {
            activeParticipants.decrementAndGet();
        }
    }

    private T executeInternal(String key, Duration boundedTimeout, ThrowingSupplier<T> supplier) throws Exception {
        CompletableFuture<T> candidate = new CompletableFuture<>();
        CompletableFuture<T> existing = inFlight.putIfAbsent(key, candidate);
        if (existing != null) {
            return await(key, existing, boundedTimeout);
        }
        try {
            T result = supplier.get();
            candidate.complete(result);
            return result;
        } catch (Throwable failure) {
            candidate.completeExceptionally(failure);
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        } finally {
            inFlight.remove(key, candidate);
        }
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public int activeParticipantCount() {
        return activeParticipants.get();
    }

    private T await(String key, CompletableFuture<T> future, Duration timeout) throws Exception {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timeoutFailure) {
            inFlight.remove(key, future);
            throw timeoutFailure;
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
