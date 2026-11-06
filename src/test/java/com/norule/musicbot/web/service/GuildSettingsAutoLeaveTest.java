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
