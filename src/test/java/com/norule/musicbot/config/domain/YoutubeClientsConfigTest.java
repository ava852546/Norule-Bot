package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YoutubeClientsConfigTest {
    @Test
    void shippedDefaultsDoNotMaterializeAnExplicitListDuringConfigurationMerge() throws Exception {
        try (var input = getClass().getResourceAsStream("/defaults/config.yml")) {
            Map<String, Object> root = new Yaml().load(input);
            Map<?, ?> music = (Map<?, ?>) root.get("music");
            Map<?, ?> youtube = (Map<?, ?>) music.get("youtube");
            assertFalse(youtube.containsKey("clients"), "Defaults must not override the conditional OAuth TV default");
        }
    }

    @Test
    void absentClientsAndExplicitSelectionRemainDistinctAndImmutable() {
        assertFalse(parse("{}").isClientsConfigured());
        var config = MusicConfig.Youtube.fromLegacy(parse("clients: [ android_music, ' WEB ', mweb ]"));
        assertTrue(config.isClientsConfigured());
        assertEquals(List.of("ANDROID_MUSIC", "WEB", "MWEB"), config.getClients());
        assertThrows(UnsupportedOperationException.class, () -> config.getClients().clear());
        assertEquals(List.of("IOS"), parse("clients: [IOS]").getClients());
    }

    @Test
    void invalidYamlValuesFailWithConfigurationPathAndWithoutEchoingInput() {
        for (String value : List.of("[]", "null", "WEB", "{}", "[WEB, web]", "[WEB, 42]",
                "[true]", "['']", "['  ']", "[null]", "[SECRET_NOT_A_CLIENT]")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> parse("clients: " + value), value);
            assertTrue(failure.getMessage().contains("music.youtube.clients"));
            assertFalse(failure.getMessage().contains("SECRET_NOT_A_CLIENT"));
        }
    }

    @Test
    void globalSelectionWinsOverGuildConfiguration() {
        var global = BotConfig.Music.fromMap(Map.of("youtube", Map.of("clients", List.of("WEB"))), null);
        var guild = BotConfig.Music.fromMap(Map.of("youtube", Map.of("clients", List.of("IOS"))), global);
        assertEquals(List.of("WEB"), MusicConfig.fromLegacy(guild, global).getYoutube().getClients());
        var inherited = BotConfig.Music.Youtube.fromMap(Map.of(), global.getYoutube());
        assertTrue(inherited.isClientsConfigured());
        assertEquals(List.of("WEB"), inherited.getClients());
    }

    private static BotConfig.Music.Youtube parse(String yaml) {
        Map<String, Object> map = new Yaml().load(yaml);
        return BotConfig.Music.Youtube.fromMap(map, null);
    }
}
