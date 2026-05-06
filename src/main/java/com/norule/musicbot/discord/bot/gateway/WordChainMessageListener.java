package com.norule.musicbot.discord.bot.gateway;

import com.norule.musicbot.discord.bot.flow.WordChainMessageFlow;
import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class WordChainMessageListener extends ListenerAdapter {
    private final WordChainMessageFlow flow;

    public WordChainMessageListener(WordChainOps wordChainOps) {
        this.flow = new WordChainMessageFlow(wordChainOps);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        flow.run(event);
    }
}

