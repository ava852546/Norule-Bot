package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver;
import com.norule.musicbot.domain.music.MusicPanelMode;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelFallbackNoticeTest {
    @TempDir Path directory;

    @Test
    void everyFallbackPlaySendsItsOwnEphemeralNoticeEvenWhenDeliveryFails() {
        List<String> sent = new ArrayList<>();
        var mode = new MusicPanelModeResolver.Selection(MusicPanelMode.MODERN, MusicPanelMode.LEGACY,
                null, null, "NOT_CONFIGURED");
        InteractionHook hook = hook(sent);
        MusicPlaybackCommandHandler.sendFallbackNotice(mode, hook, () -> "notice", 1);
        MusicPlaybackCommandHandler.sendFallbackNotice(mode, hook, () -> "notice", 1);
        assertEquals(List.of("notice", "notice"), sent);
    }

    @Test
    void validModernAndExplicitLegacyNeverSendTheFallbackNotice() {
        List<String> sent = new ArrayList<>();
        for (var mode : List.of(
                new MusicPanelModeResolver.Selection(MusicPanelMode.MODERN, MusicPanelMode.MODERN, 10L, null, "AVAILABLE"),
                new MusicPanelModeResolver.Selection(MusicPanelMode.LEGACY, MusicPanelMode.LEGACY, null, null, "LEGACY_MODE"))) {
            MusicPlaybackCommandHandler.sendFallbackNotice(mode, hook(sent), () -> fail("Unexpected text lookup"), 1);
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    void synchronousNoticeFailureCannotInterruptThePlayContinuation() {
        InteractionHook hook = (InteractionHook) Proxy.newProxyInstance(InteractionHook.class.getClassLoader(),
                new Class<?>[]{InteractionHook.class}, (o, m, a) -> { throw new IllegalStateException("expired hook"); });
        var mode = new MusicPanelModeResolver.Selection(MusicPanelMode.MODERN, MusicPanelMode.LEGACY,
                null, null, "NOT_CONFIGURED");
        assertDoesNotThrow(() -> MusicPlaybackCommandHandler.sendFallbackNotice(mode, hook, () -> "notice", 1));
    }

    @Test
    void localizedNoticeExplainsEveryPlaySettingsAndLegacyOptOutAndPreservesReloadFallback() throws Exception {
        AtomicReference<I18nService> i18n = new AtomicReference<>(I18nService.load(directory, "en"));
        var resolver = new MusicTextResolver(i18n::get);
        assertEquals("新版模式（預設）", resolver.musicText("zh-TW", "panel_mode_modern"));
        assertEquals("新版模式（默认）", resolver.musicText("zh-CN", "panel_mode_modern"));
        for (String language : List.of("en", "zh-TW", "zh-CN")) {
            String text = resolver.musicText(language, "panel_fallback_legacy");
            assertTrue(text.contains("/play"));
            assertTrue(text.contains("/settings"));
            assertTrue(text.contains(language.equals("en") ? "Legacy" : "LEGACY"));
            assertFalse(text.contains("???"));
        }
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_fallback_legacy: music.panel_fallback_legacy\n");
        i18n.set(I18nService.load(directory, "en"));
        assertTrue(resolver.musicText("en", "panel_fallback_legacy").contains("every /play"));
        Files.writeString(directory.resolve("en.yml"), "music:\n  panel_fallback_legacy: Reloaded notice\n");
        i18n.set(I18nService.load(directory, "en"));
        assertEquals("Reloaded notice", resolver.musicText("en", "panel_fallback_legacy"));
    }

    @SuppressWarnings("unchecked")
    private InteractionHook hook(List<String> sent) {
        return (InteractionHook) Proxy.newProxyInstance(InteractionHook.class.getClassLoader(),
                new Class<?>[]{InteractionHook.class}, (o, m, a) -> {
                    assertEquals("sendMessage", m.getName());
                    sent.add((String) a[0]);
                    return Proxy.newProxyInstance(m.getReturnType().getClassLoader(), new Class<?>[]{m.getReturnType()},
                            (action, method, arguments) -> {
                                if (method.getName().equals("setEphemeral")) {
                                    assertEquals(true, arguments[0]);
                                    return action;
                                }
                                assertEquals("queue", method.getName());
                                ((Consumer<Throwable>) arguments[1]).accept(new IllegalStateException("delivery failed"));
                                return null;
                            });
                });
    }
}
