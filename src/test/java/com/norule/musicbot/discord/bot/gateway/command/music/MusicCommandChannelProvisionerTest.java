package com.norule.musicbot.discord.bot.gateway.command.music;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicCommandChannelProvisionerTest {
    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

    @AfterEach
    void shutdownSchedulers() {
        schedulers.forEach(ScheduledExecutorService::shutdownNow);
    }

    @Test
    void skipsGuildWithoutManageChannelsBeforeQueueing() throws InterruptedException {
        TestChannelState state = new TestChannelState();
        GuildFixture fixture = new GuildFixture(101L, false, true);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 10L);

        boolean queued = provisioner.queueProvisioning(fixture.guild(), (guild, channel) -> {
        });

        assertFalse(queued);
        assertFalse(fixture.createStarted().await(100, TimeUnit.MILLISECONDS));
        assertEquals(0, fixture.createCalls());
        assertEquals(0, fixture.retrieveCalls());
    }

    @Test
    void provisionsGuildWithManageChannels() throws Exception {
        TestChannelState state = new TestChannelState();
        GuildFixture fixture = new GuildFixture(201L, true, true);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 10L);

        TextChannel channel = provisioner.ensureCommandChannel(fixture.guild()).get(1, TimeUnit.SECONDS);

        assertSame(fixture.createdChannel(), channel);
        assertEquals(1, fixture.createCalls());
        assertEquals(fixture.createdChannel().getIdLong(), state.configuredChannelId(201L));
        assertEquals(0, fixture.retrieveCalls());
    }

    @Test
    void usesConfiguredChannelFromJdaCacheWithoutCreating() throws Exception {
        TestChannelState state = new TestChannelState();
        GuildFixture fixture = new GuildFixture(301L, true, true);
        TextChannel configuredChannel = fixture.addCachedChannel(3_001L);
        state.rememberCommandChannel(301L, 3_001L);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 10L);

        TextChannel channel = provisioner.ensureCommandChannel(fixture.guild()).get(1, TimeUnit.SECONDS);

        assertSame(configuredChannel, channel);
        assertEquals(0, fixture.createCalls());
        assertEquals(0, fixture.retrieveCalls());
    }

    @Test
    void recreatesChannelWhenConfiguredChannelIsMissingFromCache() throws Exception {
        TestChannelState state = new TestChannelState();
        state.rememberCommandChannel(401L, 4_001L);
        GuildFixture fixture = new GuildFixture(401L, true, true);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 10L);

        TextChannel channel = provisioner.ensureCommandChannel(fixture.guild()).get(1, TimeUnit.SECONDS);

        assertSame(fixture.createdChannel(), channel);
        assertEquals(1, fixture.createCalls());
        assertEquals(fixture.createdChannel().getIdLong(), state.configuredChannelId(401L));
        assertEquals(0, fixture.retrieveCalls());
    }

    @Test
    void spacesStartupProvisioningInGuildOrder() throws InterruptedException {
        TestChannelState state = new TestChannelState();
        GuildFixture first = new GuildFixture(501L, true, true);
        GuildFixture second = new GuildFixture(502L, true, true);
        GuildFixture third = new GuildFixture(503L, true, true);
        long intervalMs = 80L;
        MusicCommandChannelProvisioner provisioner = provisioner(state, intervalMs);
        CountDownLatch completed = new CountDownLatch(3);

        provisioner.queueStartupProvisioning(
                List.of(first.guild(), second.guild(), third.guild()),
                (guild, channel) -> completed.countDown()
        );

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        List<Long> starts = List.of(
                first.createTimestamps().getFirst(),
                second.createTimestamps().getFirst(),
                third.createTimestamps().getFirst()
        );
        assertTrue(starts.get(1) - starts.get(0) >= intervalMs / 2L);
        assertTrue(starts.get(2) - starts.get(1) >= intervalMs / 2L);
        assertEquals(1, first.createCalls());
        assertEquals(1, second.createCalls());
        assertEquals(1, third.createCalls());
    }

    @Test
    void ignoresDuplicateRequestWhileGuildProvisioningIsPending() throws InterruptedException {
        TestChannelState state = new TestChannelState();
        GuildFixture fixture = new GuildFixture(601L, true, false);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 10L);
        CountDownLatch completed = new CountDownLatch(1);

        assertTrue(provisioner.queueProvisioning(
                fixture.guild(),
                (guild, channel) -> completed.countDown()
        ));
        assertTrue(fixture.createStarted().await(1, TimeUnit.SECONDS));
        assertFalse(provisioner.queueProvisioning(fixture.guild(), (guild, channel) -> {
        }));
        assertEquals(1, fixture.createCalls());

        fixture.completeCreation();

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, fixture.createCalls());
    }

    @Test
    void rechecksPermissionWhenQueuedTaskRuns() throws InterruptedException {
        TestChannelState state = new TestChannelState();
        GuildFixture blocker = new GuildFixture(701L, true, false);
        GuildFixture permissionRevoked = new GuildFixture(702L, true, true);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 30L);

        provisioner.queueStartupProvisioning(
                List.of(blocker.guild(), permissionRevoked.guild()),
                (guild, channel) -> {
                }
        );
        assertTrue(blocker.createStarted().await(1, TimeUnit.SECONDS));
        permissionRevoked.setManageChannels(false);

        assertFalse(permissionRevoked.createStarted().await(200, TimeUnit.MILLISECONDS));
        assertEquals(0, permissionRevoked.createCalls());
        blocker.completeCreation();
    }

    private MusicCommandChannelProvisioner provisioner(TestChannelState state, long intervalMs) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        return new MusicCommandChannelProvisioner(state, scheduler, intervalMs);
    }

    private static final class TestChannelState implements MusicCommandChannelProvisioner.ChannelState {
        private final Map<Long, Long> configuredChannels = new ConcurrentHashMap<>();

        @Override
        public Long configuredChannelId(long guildId) {
            return configuredChannels.get(guildId);
        }

        @Override
        public void rememberCommandChannel(long guildId, long channelId) {
            configuredChannels.put(guildId, channelId);
        }
    }

    private static final class GuildFixture implements InvocationHandler {
        private final long guildId;
        private final AtomicBoolean manageChannels;
        private final boolean autoCompleteCreation;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicInteger createCalls = new AtomicInteger();
        private final AtomicInteger retrieveCalls = new AtomicInteger();
        private final List<Long> createTimestamps = new CopyOnWriteArrayList<>();
        private final CountDownLatch createStarted = new CountDownLatch(1);
        private final Map<Long, TextChannel> cachedChannels = new ConcurrentHashMap<>();
        private final AtomicReference<Consumer<? super TextChannel>> pendingSuccess = new AtomicReference<>();
        private final Guild guild;
        private final JDA jda;
        private final SelfMember selfMember;
        private final TextChannel createdChannel;
        private final ChannelAction<TextChannel> channelAction;

        private GuildFixture(long guildId, boolean manageChannels, boolean autoCompleteCreation) {
            this.guildId = guildId;
            this.manageChannels = new AtomicBoolean(manageChannels);
            this.autoCompleteCreation = autoCompleteCreation;
            this.createdChannel = textChannel(guildId * 10L + 1L);
            this.selfMember = proxy(SelfMember.class, this::invokeSelfMember);
            this.jda = proxy(JDA.class, this::invokeJda);
            this.guild = proxy(Guild.class, this);
            this.channelAction = channelAction();
        }

        private Guild guild() {
            return guild;
        }

        private TextChannel createdChannel() {
            return createdChannel;
        }

        private int createCalls() {
            return createCalls.get();
        }

        private int retrieveCalls() {
            return retrieveCalls.get();
        }

        private List<Long> createTimestamps() {
            return createTimestamps;
        }

        private CountDownLatch createStarted() {
            return createStarted;
        }

        private void setManageChannels(boolean allowed) {
            manageChannels.set(allowed);
        }

        private TextChannel addCachedChannel(long channelId) {
            TextChannel channel = textChannel(channelId);
            cachedChannels.put(channelId, channel);
            return channel;
        }

        private void completeCreation() {
            Consumer<? super TextChannel> success = pendingSuccess.getAndSet(null);
            if (success != null) {
                success.accept(createdChannel);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getIdLong" -> guildId;
                case "getJDA" -> jda;
                case "getSelfMember" -> selfMember;
                case "getTextChannelById" -> cachedChannels.get(((Number) args[0]).longValue());
                case "getTextChannelsByName" -> List.of();
                case "createTextChannel" -> {
                    createCalls.incrementAndGet();
                    createTimestamps.add(System.currentTimeMillis());
                    createStarted.countDown();
                    yield channelAction;
                }
                default -> objectOrDefault(proxy, method, args);
            };
        }

        private Object invokeSelfMember(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getIdLong" -> 99L;
                case "hasPermission" -> hasPermission(args);
                default -> objectOrDefault(proxy, method, args);
            };
        }

        private boolean hasPermission(Object[] args) {
            if (args != null && args.length == 1 && args[0] instanceof Permission[] permissions) {
                for (Permission permission : permissions) {
                    if (permission == Permission.MANAGE_CHANNEL) {
                        return manageChannels.get();
                    }
                }
            }
            return true;
        }

        private Object invokeJda(Object proxy, Method method, Object[] args) {
            if ("getGuildById".equals(method.getName())) {
                return active.get() && ((Number) args[0]).longValue() == guildId ? guild : null;
            }
            if (method.getName().startsWith("retrieve")) {
                retrieveCalls.incrementAndGet();
            }
            return objectOrDefault(proxy, method, args);
        }

        @SuppressWarnings("unchecked")
        private ChannelAction<TextChannel> channelAction() {
            AtomicReference<ChannelAction<TextChannel>> self = new AtomicReference<>();
            ChannelAction<TextChannel> action = proxy(ChannelAction.class, (proxy, method, args) -> {
                if ("addMemberPermissionOverride".equals(method.getName())) {
                    return self.get();
                }
                if ("queue".equals(method.getName()) && args != null && args.length == 2) {
                    Consumer<? super TextChannel> success = (Consumer<? super TextChannel>) args[0];
                    if (autoCompleteCreation) {
                        success.accept(createdChannel);
                    } else {
                        pendingSuccess.set(success);
                    }
                    return null;
                }
                return objectOrDefault(proxy, method, args);
            });
            self.set(action);
            return action;
        }

        private static TextChannel textChannel(long channelId) {
            return proxy(TextChannel.class, (proxy, method, args) -> {
                if ("getIdLong".equals(method.getName())) {
                    return channelId;
                }
                return objectOrDefault(proxy, method, args);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object objectOrDefault(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> primitiveDefault(method.getReturnType());
        };
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return 0.0d;
    }
}
