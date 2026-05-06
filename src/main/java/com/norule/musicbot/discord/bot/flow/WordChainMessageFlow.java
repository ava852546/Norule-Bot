package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public final class WordChainMessageFlow {
    private final WordChainOps wordChainOps;

    public WordChainMessageFlow(WordChainOps wordChainOps) {
        this.wordChainOps = wordChainOps;
    }

    public void run(MessageReceivedEvent event) {
        wordChainOps.handleMessage(event);
    }
}

