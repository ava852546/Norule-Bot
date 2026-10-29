package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;

import java.util.List;
import java.util.ArrayList;

/** The exact immutable payload used both for comparison and delivery. */
public record MusicPanelSnapshot(MessageEmbed embed, List<ActionRow> components) {
    public MusicPanelSnapshot {
        components = List.copyOf(components);
    }

    public boolean sameContent(MusicPanelSnapshot other) {
        return other != null && payload().equals(other.payload());
    }

    private java.util.Map<String, Object> payload() {
        // Discord assigns numeric IDs on receipt; custom IDs carry button routing semantics.
        var comparableRows = new ArrayList<ActionRow>();
        int uniqueId = 1;
        for (var row : components) {
            var children = new ArrayList<ActionRowChildComponent>();
            for (var child : row.getComponents()) {
                children.add(child.withUniqueId(uniqueId++));
            }
            comparableRows.add(row.withComponents(children).withUniqueId(uniqueId++));
        }
        try (var data = new MessageEditBuilder().setEmbeds(embed).setComponents(comparableRows).build()) {
            return data.toData().toMap();
        }
    }
}
