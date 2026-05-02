package com.norule.musicbot.discord.gateway;

public interface Signals {
    <T> void on(Class<T> type, SignalHandler<T> handler);
    void publish(Object signal);
}

