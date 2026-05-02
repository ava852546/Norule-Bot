package com.norule.musicbot.discord.gateway;

@FunctionalInterface
public interface SignalHandler<T> {
    void handle(T signal);
}

