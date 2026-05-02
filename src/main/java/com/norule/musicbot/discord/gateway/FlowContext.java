package com.norule.musicbot.discord.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FlowContext {
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public FlowContext put(String key, Object value) {
        if (key != null && value != null) {
            values.put(key, value);
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null || type == null || !type.isInstance(value)) {
            return null;
        }
        return (T) value;
    }
}

