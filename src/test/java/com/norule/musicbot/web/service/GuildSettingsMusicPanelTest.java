package com.norule.musicbot.web.service;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import com.norule.musicbot.web.TestHttpExchange;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.infra.WebSettings;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GuildSettingsMusicPanelTest {
    @TempDir Path temp;
    Set<Permission> denied = Set.of();
    boolean cached = true;
    final SelfMember self = proxy(SelfMember.class, (o, m, a) -> {
        assertEquals("hasPermission", m.getName());
        return Arrays.stream((Permission[]) a[1]).noneMatch(denied::contains);
    });
    final Guild guild = proxy(Guild.class, (o, m, a) -> switch (m.getName()) {
        case "getIdLong" -> 1L;
        case "getSelfMember" -> self;
        case "getTextChannelById" -> cached ? this.channel : null;
        case "getNewsChannelById", "getThreadChannelById", "getVoiceChannelById", "getGuildChannelById" -> null;
        default -> throw new AssertionError("Unexpected Discord request: " + m.getName());
    });
    final TextChannel channel = proxy(TextChannel.class, (o, m, a) -> switch (m.getName()) {
        case "getGuild" -> guild;
        case "getIdLong" -> 10L;
        case "getType" -> ChannelType.TEXT;
        default -> throw new AssertionError("Unexpected channel request: " + m.getName());
    });

    @Test
    void backendRejectsMissingPermissionsAndUnverifiedChannelsBeforeAnyMutation() throws Exception {
        var settings = settings();
        try (var fixture = new Server(settings)) {
            for (var permission : Set.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
                denied = Set.of(permission);
                var exchange = save(fixture.service, "10");
                assertEquals(400, exchange.responseCode());
                var response = DataObject.fromJson(exchange.responseBodyUtf8());
                assertEquals("MUSIC_PANEL_CHANNEL_UNAVAILABLE", response.getString("errorCode"));
                assertEquals(permission.name(), response.getArray("missingPermissions").getString(0));
                assertTrue(response.getString("error").contains("/settings"));
                assertNull(settings.getMusic(1).getCommandChannelId());
                assertEquals("en", settings.getLanguage(1));
            }
            cached = false;
            var missing = save(fixture.service, "10");
            assertEquals(400, missing.responseCode());
            assertEquals("CHANNEL_NOT_CACHED", DataObject.fromJson(missing.responseBodyUtf8()).getString("reason"));
            assertFalse(missing.responseBodyUtf8().contains("confirmed the channel does not exist"));
        }
    }

    @Test
    void validSaveAndLaterPermissionChangesShowFallbackAndRecoverWithoutChangingSettings() throws Exception {
        var settings = settings();
        try (var fixture = new Server(settings)) {
            assertEquals(200, save(fixture.service, "10").responseCode());
            denied = Set.of(Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
            var blocked = musicPayload(fixture.service, settings);
            assertEquals("MODERN", blocked.getString("musicPanelMode"));
            assertEquals("LEGACY", blocked.getString("musicPanelEffectiveMode"));
            assertEquals("MISSING_SEND_PERMISSION", blocked.getString("musicPanelReason"));
            assertEquals(2, blocked.getArray("musicPanelMissingPermissions").length());
            assertEquals("10", blocked.getString("commandChannelId"));
            // An unrelated settings save must retain an existing temporarily invalid channel.
            assertEquals(200, save(fixture.service, "10").responseCode());
            denied = Set.of();
            assertEquals("MODERN", musicPayload(fixture.service, settings).getString("musicPanelEffectiveMode"));
            assertEquals(10L, settings.getMusic(1).getCommandChannelId());
            assertNull(settings.getMusic(2).getCommandChannelId());
        }
    }

    private TestHttpExchange save(GuildSettingsWebService service, String id) throws Exception {
        var exchange = new TestHttpExchange("POST", "/api/guild/1/settings",
                ("{\"music\":{\"commandChannelId\":\"" + id + "\"}}").getBytes(StandardCharsets.UTF_8));
        service.handleSettingsSave(exchange, guild);
        return exchange;
    }

    @Test
    void webUsesSharedVerificationEvidenceAndDoesNotConfuseTransientFailureWithDeletion() throws Exception {
        var settings = settings();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(10L)));
        cached = false;
        try (var fixture = new Server(settings)) {
            for (String reason : Set.of("UNKNOWN_CHANNEL", "IOException", "CHANNEL_VERIFICATION_PENDING")) {
                fixture.owner.setMusicPanelStatusResolver(g -> new com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver.Selection(
                        com.norule.musicbot.domain.music.MusicPanelMode.MODERN,
                        com.norule.musicbot.domain.music.MusicPanelMode.LEGACY, 10L, null, reason));
                var payload = musicPayload(fixture.service, settings);
                assertEquals(reason, payload.getString("musicPanelReason"));
                assertEquals(reason.equals("UNKNOWN_CHANNEL") ? "panel_reason_deleted" : "panel_reason_verification",
                        payload.getString("musicPanelReasonKey"));
            }
            fixture.owner.setMusicPanelStatusResolver(g -> new com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver.Selection(
                    com.norule.musicbot.domain.music.MusicPanelMode.MODERN,
                    com.norule.musicbot.domain.music.MusicPanelMode.MODERN, 10L, channel, "AVAILABLE"));
            assertTrue(musicPayload(fixture.service, settings).getBoolean("musicPanelChannelValid"),
                    "A shared REST proof must remain valid even when the channel is absent from cache");
        }
    }

    private DataObject musicPayload(GuildSettingsWebService service, GuildSettingsService settings) throws Exception {
        var method = GuildSettingsWebService.class.getDeclaredMethod("buildMusicPayload", Guild.class, GuildSettingsService.GuildSettings.class);
        method.setAccessible(true);
        return (DataObject) method.invoke(service, guild, settings.getSettings(1));
    }

    private GuildSettingsService settings() {
        return new GuildSettingsService(temp.resolve("guilds"), new BotConfig("", null, "&", false,
                null, null, null, null, "en", 0, 0, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    private final class Server implements AutoCloseable {
        final WebControlServer owner;
        final GuildSettingsWebService service;
        Server(GuildSettingsService settings) throws Exception {
            var repository = proxy(ShortUrlRepository.class, (o, m, a) -> null);
            owner = new WebControlServer(null, null, settings, null, null, new ShortUrlService(repository),
                    () -> new WebSettings(false, 60000, "https://example.com", 60, "", "", ""),
                    null, () -> temp.toString(), I18nService.load(temp.resolve("lang"), "en"));
            service = new GuildSettingsWebService(owner);
        }
        public void close() { owner.shutdown(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
