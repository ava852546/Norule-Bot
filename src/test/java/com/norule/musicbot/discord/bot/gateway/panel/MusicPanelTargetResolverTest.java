package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelTargetResolverTest {
    Permission denied;
    boolean archived;
    boolean locked;
    boolean writable = true;
    GuildMessageChannel cached;
    final SelfMember self = proxy(SelfMember.class, (o, m, a) -> switch (m.getName()) {
        case "hasPermission" -> !Arrays.asList((Permission[]) a[1]).contains(denied);
        default -> throw new UnsupportedOperationException(m.getName());
    });
    final Guild guild = proxy(Guild.class, (o, m, a) -> switch (m.getName()) {
        case "getIdLong" -> 1L;
        case "getSelfMember" -> self;
        case "getTextChannelById" -> cached instanceof TextChannel ? cached : null;
        case "getNewsChannelById" -> cached instanceof NewsChannel ? cached : null;
        case "getThreadChannelById" -> cached instanceof ThreadChannel ? cached : null;
        case "getGuildChannelById" -> cached;
        default -> throw new UnsupportedOperationException(m.getName());
    });

    static Stream<Class<? extends GuildMessageChannel>> supported() {
        return Stream.of(TextChannel.class, NewsChannel.class, ThreadChannel.class);
    }

    @ParameterizedTest
    @MethodSource("supported")
    void supportsCachedAndRetainedMessageChannels(Class<? extends GuildMessageChannel> type) {
        var channel = channel(type);
        cached = channel;
        assertTrue(MusicPanelTargetResolver.resolve(guild, 10L, null).available());
        cached = null;
        var result = MusicPanelTargetResolver.resolve(guild, 10L, channel);
        assertTrue(result.available());
        assertFalse(result.cacheHit());
        assertSame(channel, result.channel());
        assertEquals("CHANNEL_NOT_CACHED", MusicPanelTargetResolver.resolve(guild, 10L, null).reason());
    }

    @ParameterizedTest
    @CsvSource({"VIEW_CHANNEL,MISSING_VIEW_PERMISSION", "MESSAGE_SEND,MISSING_SEND_PERMISSION",
            "MESSAGE_EMBED_LINKS,MISSING_EMBED_PERMISSION"})
    void reportsIndividualMissingPermissions(Permission permission, String reason) {
        denied = permission;
        cached = channel(TextChannel.class);
        assertEquals(reason, MusicPanelTargetResolver.resolve(guild, 10L, null).reason());
    }

    @Test
    void threadUsesThreadSendPermissionAndDistinguishesArchivedLockedAndNotWritable() {
        cached = channel(ThreadChannel.class);
        denied = Permission.MESSAGE_SEND;
        assertTrue(MusicPanelTargetResolver.resolve(guild, 10L, null).available());
        denied = Permission.MESSAGE_SEND_IN_THREADS;
        assertEquals("MISSING_SEND_PERMISSION", MusicPanelTargetResolver.resolve(guild, 10L, null).reason());
        denied = null;
        archived = true;
        assertEquals("THREAD_ARCHIVED", MusicPanelTargetResolver.resolve(guild, 10L, null).reason());
        locked = true;
        assertEquals("THREAD_LOCKED", MusicPanelTargetResolver.resolve(guild, 10L, null).reason());
        archived = locked = false;
        writable = false;
        assertEquals("CHANNEL_NOT_WRITABLE", MusicPanelTargetResolver.resolve(guild, 10L, null).reason());
    }

    @Test
    void unsupportedAndMissingChannelsRemainDistinctAndNullDoesNotUnbox() {
        cached = channel(VoiceChannel.class);
        assertEquals("UNSUPPORTED_CHANNEL_TYPE", MusicPanelTargetResolver.resolve(guild, 10L, null).reason());
        assertEquals("NO_INTERACTION_CHANNEL", MusicPanelTargetResolver.resolve(guild, null, cached).reason());
        var unrelated = channel(TextChannel.class);
        cached = null;
        assertEquals("CHANNEL_NOT_CACHED", MusicPanelTargetResolver.resolve(guild, 20L, unrelated).reason());
    }

    <T extends GuildMessageChannel> T channel(Class<T> type) {
        return proxy(type, (o, m, a) -> switch (m.getName()) {
            case "getGuild" -> guild;
            case "getIdLong" -> 10L;
            case "getType" -> type == ThreadChannel.class ? ChannelType.GUILD_PUBLIC_THREAD
                    : type == NewsChannel.class ? ChannelType.NEWS : type == VoiceChannel.class ? ChannelType.VOICE : ChannelType.TEXT;
            case "isArchived" -> archived;
            case "isLocked" -> locked;
            case "canTalk" -> writable;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
