package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelSnapshotTest {
    @Test
    void separatelyRenderedIdenticalContentMatches() {
        assertTrue(snapshot(builder -> {}).sameContent(snapshot(builder -> {})));
    }

    @Test
    void comparesEveryVisibleEmbedPart() {
        var original = snapshot(builder -> {});
        for (Consumer<EmbedBuilder> change : List.<Consumer<EmbedBuilder>>of(
                builder -> builder.setTitle("new title"),
                builder -> builder.setDescription("new description"),
                builder -> builder.addField("queue", "track B", false),
                builder -> builder.setFooter("new footer"),
                builder -> builder.setThumbnail("https://example.test/thumbnail.png"),
                builder -> builder.setImage("https://example.test/image.png"),
                builder -> builder.setColor(123))) {
            assertFalse(original.sameContent(snapshot(change)));
        }
    }

    @Test
    void comparesButtonLabelsDisabledStateAndSelectOptions() {
        var embed = new EmbedBuilder().setTitle("panel").build();
        var original = new MusicPanelSnapshot(embed, List.of(ActionRow.of(Button.primary("pause", "Pause"))));
        assertFalse(original.sameContent(new MusicPanelSnapshot(embed,
                List.of(ActionRow.of(Button.primary("pause", "Resume"))))));
        assertFalse(original.sameContent(new MusicPanelSnapshot(embed,
                List.of(ActionRow.of(Button.primary("pause", "Pause").asDisabled())))));
        var menu = new MusicPanelSnapshot(embed, List.of(ActionRow.of(
                StringSelectMenu.create("queue").addOption("Track A", "a").build())));
        assertFalse(menu.sameContent(new MusicPanelSnapshot(embed, List.of(ActionRow.of(
                StringSelectMenu.create("queue").addOption("Track B", "b").build())))));
        assertTrue(menu.sameContent(new MusicPanelSnapshot(embed, List.of(ActionRow.of(
                StringSelectMenu.create("queue").addOption("Track A", "a").build())))));
    }

    private MusicPanelSnapshot snapshot(Consumer<EmbedBuilder> change) {
        EmbedBuilder builder = new EmbedBuilder().setTitle("panel").setDescription("volume 30")
                .setFooter("footer").addField("queue", "track A", false);
        change.accept(builder);
        return new MusicPanelSnapshot(builder.build(), List.of(ActionRow.of(Button.primary("pause", "Pause"))));
    }
}
