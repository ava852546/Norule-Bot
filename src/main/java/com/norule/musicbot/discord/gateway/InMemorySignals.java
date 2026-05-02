package com.norule.musicbot.discord.gateway;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemorySignals implements Signals {
    private final Map<Class<?>, List<SignalHandler<?>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <T> void on(Class<T> type, SignalHandler<T> handler) {
        if (type == null || handler == null) {
            return;
        }
        subscribers.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void publish(Object signal) {
        if (signal == null) {
            return;
        }
        List<SignalHandler<?>> handlers = subscribers.get(signal.getClass());
        if (handlers == null) {
            return;
        }
        for (SignalHandler<?> raw : handlers) {
            try {
                ((SignalHandler<Object>) raw).handle(signal);
            } catch (Exception ignored) {
            }
        }
    }
}

