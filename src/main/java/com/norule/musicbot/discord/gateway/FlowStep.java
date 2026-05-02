package com.norule.musicbot.discord.gateway;

public interface FlowStep<E> {
    boolean apply(E envelope, FlowContext context);
}

