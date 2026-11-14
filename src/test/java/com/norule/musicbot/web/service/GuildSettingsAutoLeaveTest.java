package com.norule.musicbot.web.service;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import com.norule.musicbot.web.TestHttpExchange;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.infra.WebSettings;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class GuildSettingsAutoLeaveTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @Test
    void rejectedPrivateRoomEnableKeepsOtherSectionSaveAndReturnsLocalizedWarning() throws Exception {
        var defaults = new com.norule.musicbot.config.BotConfig("", null, "&", false, null, null, null, null, "en", 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        var settings = new com.norule.musicbot.config.GuildSettingsService(temp.resolve("guilds"), defaults);
        settings.updateSettings(1, current -> current.withPrivateRoom(current.getPrivateRoom().withEnabled(false)));
        var repository = (ShortUrlRepository) Proxy.newProxyInstance(ShortUrlRepository.class.getClassLoader(),
                new Class<?>[]{ShortUrlRepository.class}, (proxy, method, args) -> null);
        var guild = (net.dv8tion.jda.api.entities.Guild) Proxy.newProxyInstance(
                net.dv8tion.jda.api.entities.Guild.class.getClassLoader(),
                new Class<?>[]{net.dv8tion.jda.api.entities.Guild.class}, (proxy, method, args) ->
                        method.getName().equals("getIdLong") ? 1L : null);
        var owner = new WebControlServer(null, null, settings, null, null, new ShortUrlService(repository),
                () -> new WebSettings(false, 60000, "https://example.com", 60, "", "", ""),
                null, () -> temp.toString(), com.norule.musicbot.i18n.I18nService.load(temp.resolve("lang"), "en"));
        try {
            var exchange = new TestHttpExchange("POST", "/api/guild/1/settings", ("""
                    {"privateRoom":{"enabled":true,"triggerVoiceChannelId":"20"},"music":{"autoplayEnabled":false}}
                    """).getBytes(StandardCharsets.UTF_8));
            new GuildSettingsWebService(owner).handleSettingsSave(exchange, guild);
            assertEquals(200, exchange.responseCode());
            assertTrue(exchange.responseBodyUtf8().contains("privateRoomWarning"));
            assertTrue(exchange.responseBodyUtf8().contains("Cannot enable private voice rooms"));
            assertFalse(settings.getPrivateRoom(1).isEnabled());
            assertFalse(settings.getMusic(1).isAutoplayEnabled());
        } finally { owner.shutdown(); }
    }

    @Test
    void rejectsRetiredFieldsBeforeAnySettingsAreMutated() throws Exception {
        var repository = (ShortUrlRepository) Proxy.newProxyInstance(ShortUrlRepository.class.getClassLoader(),
                new Class<?>[]{ShortUrlRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findLogChannelId")) return null;
                    throw new AssertionError("Unexpected repository access: " + method.getName());
                });
        var owner = new WebControlServer(null, null, null, null, null, new ShortUrlService(repository),
                () -> new WebSettings(false, 60000, "https://example.com", 60, "", "", ""),
                null, () -> "lang", null);
        try {
            var service = new GuildSettingsWebService(owner);
            for (String field : new String[]{"\"autoLeaveEnabled\":false", "\"autoLeaveEnabled\":true",
                    "\"autoLeaveMinutes\":1", "\"autoLeaveEnabled\":\"false\""}) {
                var exchange = new TestHttpExchange("POST", "/api/guild/1/settings",
                        ("{\"music\":{" + field + ",\"autoplayEnabled\":false}}").getBytes(StandardCharsets.UTF_8));
                service.handleSettingsSave(exchange, null);
                assertEquals(400, exchange.responseCode());
                assertTrue(exchange.responseBodyUtf8().contains("fixed system policy"));
            }
        } finally {
            owner.shutdown();
        }
    }
}
