package com.norule.musicbot.discord.bot.service.privateroom;

import com.norule.musicbot.config.*;
import com.norule.musicbot.discord.bot.service.cache.PrivateRoomCacheRepository;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class PrivateRoomPermissionsTest {
    @TempDir Path temp;

    @Test void effectiveCategoryAndTriggerPermissionsAreValidatedAndLocalized() {
        var fixture = new Fixture();
        fixture.missing.add(Permission.MANAGE_CHANNEL);
        fixture.missing.add(Permission.VOICE_MOVE_OTHERS);
        var check = PrivateRoomPermissions.check(fixture.guild, 20L);
        assertFalse(check.allowed());
        assertTrue(check.missing().containsAll(fixture.missing));
        var i18n = I18nService.load(temp, "en");
        for (String language : List.of("zh-TW", "zh-CN", "en")) {
            String message = check.message(i18n, language);
            assertFalse(message.contains("room_settings."));
            assertFalse(message.contains("{permissions}"));
            assertTrue(message.contains("10") && message.contains("20") && message.contains("30"));
        }
        fixture.missing.clear(); assertTrue(PrivateRoomPermissions.check(fixture.guild, 20L).allowed());
        assertFalse(PrivateRoomPermissions.check(fixture.guild, null).allowed());
    }

    @Test void runtimePermissionRevocationPreventsCreateAndSynchronousRaceIsContained() {
        var fixture = new Fixture();
        var settings = settings();
        var service = new PrivateRoomService(settings, I18nService.load(temp.resolve("lang"), "en"),
                stub(PrivateRoomCacheRepository.class, (method, args) -> method.equals("findAll") ? List.of() : null));
        fixture.missing.add(Permission.MANAGE_CHANNEL);
        assertDoesNotThrow(() -> service.onGuildVoiceUpdate(fixture.event()));
        assertEquals(0, fixture.creates.get());
        fixture.missing.clear();
        PrivateRoomService.setRoomOwner(10, 777, 1);
        assertDoesNotThrow(() -> service.onGuildVoiceUpdate(fixture.event()));
        assertEquals(1, fixture.creates.get());
        assertTrue(PrivateRoomService.isManagedPrivateRoom(10, 777));
    }

    @Test void asynchronousPermissionFailureIsHandledWithoutRemovingExistingRooms() {
        var fixture = new Fixture(); fixture.asyncFailure = true;
        var service = new PrivateRoomService(settings(), I18nService.load(temp.resolve("lang"), "en"),
                stub(PrivateRoomCacheRepository.class, (method, args) -> method.equals("findAll") ? List.of() : null));
        PrivateRoomService.setRoomOwner(10, 777, 1);
        assertDoesNotThrow(() -> service.onGuildVoiceUpdate(fixture.event()));
        assertEquals(1, fixture.creates.get());
        assertTrue(PrivateRoomService.isManagedPrivateRoom(10, 777));
    }

    private GuildSettingsService settings() {
        var defaults = new BotConfig("", null, "&", false, null, null, null, null, "en", 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        var settings = new GuildSettingsService(temp.resolve("guilds"), defaults);
        settings.updateSettings(10, current -> current.withPrivateRoom(current.getPrivateRoom()
                .withEnabled(true).withTriggerVoiceChannelId(20L)));
        return settings;
    }

    private static final class Fixture {
        final Set<Permission> missing = EnumSet.noneOf(Permission.class);
        final AtomicInteger creates = new AtomicInteger();
        boolean asyncFailure;
        final JDA jda = stub(JDA.class, (name, args) -> null);
        final User user = stub(User.class, (name, args) -> name.equals("getName") ? "fixture" : null);
        final Category category = stub(Category.class, (name, args) -> name.equals("getIdLong") ? 30L : null);
        Guild guild;
        Member member;
        VoiceChannel trigger;
        Fixture() {
            trigger = (VoiceChannel) Proxy.newProxyInstance(VoiceChannel.class.getClassLoader(),
                    new Class<?>[]{VoiceChannel.class, AudioChannelUnion.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getIdLong" -> 20L;
                        case "getParentCategory" -> category;
                        case "getGuild" -> guild;
                        case "asVoiceChannel" -> proxy;
                        default -> defaultValue(method.getReturnType());
                    });
            var voice = stub(GuildVoiceState.class, (name, args) -> name.equals("getChannel") ? trigger : null);
            member = stub(SelfMember.class, (name, args) -> switch (name) {
                case "getGuild" -> guild;
                case "getVoiceState" -> voice;
                case "getUser" -> user;
                case "getEffectiveName" -> "fixture";
                case "hasPermission" -> Arrays.stream((Permission[]) args[args.length - 1]).noneMatch(missing::contains);
                default -> null;
            });
            guild = stub(Guild.class, (name, args) -> switch (name) {
                case "getIdLong" -> 10L;
                case "getSelfMember" -> member;
                case "getVoiceChannelById" -> trigger;
                case "getMaxBitrate" -> 64000;
                case "getPublicRole" -> stub(Role.class, (n, a) -> n.equals("getIdLong") ? 10L : null);
                case "retrieveOwner" -> stub(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class, (n, a) -> null);
                case "createVoiceChannel" -> {
                    creates.incrementAndGet();
                    if (!asyncFailure) throw new PermissionException("fixture revocation race");
                    yield Proxy.newProxyInstance(ChannelAction.class.getClassLoader(), new Class<?>[]{ChannelAction.class},
                            (proxy, method, actionArgs) -> {
                                if (method.getName().equals("queue")) {
                                    @SuppressWarnings("unchecked") var failure = (java.util.function.Consumer<Throwable>) actionArgs[1];
                                    failure.accept(new PermissionException("fixture async revocation")); return null;
                                }
                                return proxy;
                            });
                }
                default -> null;
            });
        }
        GuildVoiceUpdateEvent event() { return new GuildVoiceUpdateEvent(jda, 0, member, null); }
    }

    @SuppressWarnings("unchecked") private static <T> T stub(Class<T> type, BiFunction<String,Object[],Object> action) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object value = action.apply(method.getName(), args == null ? new Object[0] : args);
            return value != null ? value : defaultValue(method.getReturnType());
        });
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return null;
    }
}
