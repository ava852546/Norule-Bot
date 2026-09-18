package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;

import java.util.List;

/** The exact immutable payload used both for comparison and delivery. */
public record MusicPanelSnapshot(MessageEmbed embed, List<ActionRow> components) {
    public MusicPanelSnapshot {
        components = List.copyOf(components);
    }

    public boolean sameContent(MusicPanelSnapshot other) {
        return other != null && payload().equals(other.payload());
    }

    private java.util.Map<String, Object> payload() {
        try (var data = new MessageEditBuilder().setEmbeds(embed).setComponents(components).build()) {
            return data.toData().toMap();
        }
    }
}
