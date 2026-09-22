package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.domain.music.MusicCommandChannelProvisioning.Status;
import com.norule.musicbot.service.music.MusicCommandChannelProvisioningService;
import com.norule.musicbot.storage.sqlite.MusicCommandChannelProvisioningSqliteRepository;
import com.norule.musicbot.storage.sqlite.SqliteDatabase;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicCommandChannelProvisionerTest {
    @TempDir
    Path tempDir;
    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

    @AfterEach
    void shutdownSchedulers() throws InterruptedException {
        schedulers.forEach(ScheduledExecutorService::shutdownNow);
        for (ScheduledExecutorService scheduler : schedulers) {
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void recordsFailureWithoutManageChannelsAndNeverRetries() throws Exception {
        TestChannelState state = new TestChannelState();
        GuildFixture fixture = new GuildFixture(101L, false, true);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 10L);

        boolean queued = provisioner.queueProvisioning(fixture.guild(), (guild, channel) -> {
        });

        assertTrue(queued);
        drainScheduler();
        assertEquals(Status.FAILED, repository().find(101L).status());
        assertEquals("Missing permission: MANAGE_CHANNEL", repository().find(101L).failureReason());
        fixture.setManageChannels(true);
        assertFalse(provisioner(state, 0L).queueProvisioning(fixture.guild(), null));
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
    void rechecksPermissionAfterGuildJoinBeforeProvisioning() throws InterruptedException {
        TestChannelState state = new TestChannelState();
        GuildFixture fixture = new GuildFixture(251L, false, true);
        MusicCommandChannelProvisioner provisioner = provisioner(state, 80L);
        CountDownLatch completed = new CountDownLatch(1);

        assertTrue(provisioner.queueGuildJoinProvisioning(
                fixture.guild(),
                (guild, channel) -> completed.countDown()
        ));
        fixture.setManageChannels(true);

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, fixture.createCalls());
        assertEquals(fixture.createdChannel().getIdLong(), state.configuredChannelId(251L));
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
        return new MusicCommandChannelProvisioner(state, scheduler, intervalMs,
                new MusicCommandChannelProvisioningService(repository()));
    }

    private MusicCommandChannelProvisioningSqliteRepository repository() {
        return new MusicCommandChannelProvisioningSqliteRepository(new SqliteDatabase(tempDir.resolve("state.db")));
    }

    private void drainScheduler() throws Exception {
        schedulers.getLast().submit(() -> {}).get(2, TimeUnit.SECONDS);
    }

    @Test
    void newGuildAttemptsExactlyOnceAndSuccessSurvivesRestart() throws Exception {
        GuildFixture fixture = new GuildFixture(801L, true, true);
        MusicCommandChannelProvisioner first = provisioner(new TestChannelState(), 0L);
        CountDownLatch completed = new CountDownLatch(1);
        assertTrue(first.queueGuildJoinProvisioning(fixture.guild(), (guild, channel) -> completed.countDown()));
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        var saved = repository().find(801L);
        assertEquals(Status.SUCCESS, saved.status());
        assertNotNull(saved.attemptedAt());
        assertNull(saved.failureReason());
        assertFalse(first.queueProvisioning(fixture.guild(), null));

        // Reopen the same file with new repository, service, scheduler and channel state.
        MusicCommandChannelProvisioner restarted = provisioner(new TestChannelState(), 0L);
        restarted.queueStartupProvisioning(List.of(fixture.guild()), null);
        assertFalse(restarted.queueGuildJoinProvisioning(fixture.guild(), null));
        assertThrows(Exception.class, () -> restarted.ensureCommandChannel(fixture.guild()).get());
        assertEquals(1, fixture.createCalls());
        assertEquals(saved, repository().find(801L));
    }

    @Test
    void asynchronousFailureSurvivesRestart() throws Exception {
        GuildFixture fixture = new GuildFixture(802L, true, false);
        MusicCommandChannelProvisioner first = provisioner(new TestChannelState(), 0L);
        CompletableFuture<TextChannel> pending = first.ensureCommandChannel(fixture.guild());
        fixture.failCreation(new IllegalStateException("Discord API failure"));
        assertThrows(Exception.class, () -> pending.get(2, TimeUnit.SECONDS));
        var saved = repository().find(802L);
        assertEquals(Status.FAILED, saved.status());
        assertEquals("Discord API failure", saved.failureReason());
        MusicCommandChannelProvisioner restarted = provisioner(new TestChannelState(), 0L);
        restarted.queueStartupProvisioning(List.of(fixture.guild()), null);
        assertFalse(restarted.queueGuildJoinProvisioning(fixture.guild(), null));
        assertEquals(1, fixture.createCalls());
        assertEquals(saved, repository().find(802L));
    }

    @Test
    void attemptingSurvivesRestart() throws Exception {
        GuildFixture fixture = new GuildFixture(803L, true, false);
        provisioner(new TestChannelState(), 0L).ensureCommandChannel(fixture.guild());
        assertEquals(Status.ATTEMPTING, repository().find(803L).status());
        MusicCommandChannelProvisioner restarted = provisioner(new TestChannelState(), 0L);
        restarted.queueStartupProvisioning(List.of(fixture.guild()), null);
        assertFalse(restarted.queueGuildJoinProvisioning(fixture.guild(), null));
        assertEquals(1, fixture.createCalls());
    }

    @Test
    void multipleStartupEventsAttemptOnceEvenAfterCompletion() throws Exception {
        GuildFixture fixture = new GuildFixture(804L, true, true);
        MusicCommandChannelProvisioner provisioner = provisioner(new TestChannelState(), 0L);
        CountDownLatch completed = new CountDownLatch(1);
        provisioner.queueStartupProvisioning(List.of(fixture.guild()), (guild, channel) -> completed.countDown());
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) {
            provisioner.queueStartupProvisioning(List.of(fixture.guild()), null);
        }
        assertEquals(1, fixture.createCalls());
    }

    @Test
    void guildJoinAndReadyRaceAcrossIndependentRepositoriesAttemptOnce() throws Exception {
        GuildFixture fixture = new GuildFixture(805L, true, false);
        MusicCommandChannelProvisioner join = provisioner(new TestChannelState(), 0L);
        MusicCommandChannelProvisioner ready = provisioner(new TestChannelState(), 0L);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var joined = callers.submit(() -> {
                start.await();
                return join.queueGuildJoinProvisioning(fixture.guild(), (guild, channel) -> completed.countDown());
            });
            var started = callers.submit(() -> {
                start.await();
                ready.queueStartupProvisioning(List.of(fixture.guild()), (guild, channel) -> completed.countDown());
                return null;
            });
            start.countDown();
            joined.get(2, TimeUnit.SECONDS);
            started.get(2, TimeUnit.SECONDS);
        }
        // Wait for queue() as well as createTextChannel(), then release the async response.
        for (ScheduledExecutorService scheduler : schedulers) {
            scheduler.submit(() -> {}).get(2, TimeUnit.SECONDS);
        }
        assertEquals(Status.ATTEMPTING, repository().find(805L).status());
        fixture.completeCreation();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(1, fixture.createCalls());
        assertEquals(Status.SUCCESS, repository().find(805L).status());
    }

    @Test
    void guildLeaveRemovesRecordAndRejoinAttemptsAgain() throws Exception {
        GuildFixture fixture = new GuildFixture(806L, true, true);
        MusicCommandChannelProvisioner provisioner = provisioner(new TestChannelState(), 0L);
        provisioner.ensureCommandChannel(fixture.guild()).get(2, TimeUnit.SECONDS);
        provisioner.guildLeft(806L);
        assertNull(repository().find(806L));
        CountDownLatch completed = new CountDownLatch(1);
        assertTrue(provisioner.queueGuildJoinProvisioning(fixture.guild(), (guild, channel) -> completed.countDown()));
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(2, fixture.createCalls());
        assertEquals(Status.SUCCESS, repository().find(806L).status());
    }

    @Test
    void missingManagePermissionsRecordsFailureAndNeverRetriesAfterPermissionGranted() throws Exception {
        GuildFixture fixture = new GuildFixture(807L, true, true);
        fixture.managePermissions.set(false);
        MusicCommandChannelProvisioner provisioner = provisioner(new TestChannelState(), 0L);
        assertTrue(provisioner.queueGuildJoinProvisioning(fixture.guild(), null));
        drainScheduler();
        var saved = repository().find(807L);
        assertEquals(Status.FAILED, saved.status());
        assertEquals("Missing permission: MANAGE_PERMISSIONS", saved.failureReason());
        fixture.managePermissions.set(true);
        MusicCommandChannelProvisioner restarted = provisioner(new TestChannelState(), 0L);
        restarted.queueStartupProvisioning(List.of(fixture.guild()), null);
        assertFalse(restarted.queueGuildJoinProvisioning(fixture.guild(), null));
        assertThrows(Exception.class, () -> restarted.ensureCommandChannel(fixture.guild()).get());
        assertEquals(0, fixture.createCalls());
        assertEquals(saved, repository().find(807L));
    }

    @Test
    void staleCallbackCannotOverwriteRejoinedGuildAttempt() throws Exception {
        GuildFixture oldMembership = new GuildFixture(808L, true, false);
        TestChannelState channels = new TestChannelState();
        MusicCommandChannelProvisioner provisioner = provisioner(channels, 0L);
        var oldResult = provisioner.ensureCommandChannel(oldMembership.guild());
        provisioner.guildLeft(808L);
        GuildFixture newMembership = new GuildFixture(808L, true, false);
        var newResult = provisioner.ensureCommandChannel(newMembership.guild());
        var current = repository().find(808L);
        oldMembership.completeCreation();
        assertThrows(Exception.class, () -> oldResult.get(2, TimeUnit.SECONDS));
        assertEquals(current, repository().find(808L));
        assertNull(channels.configuredChannelId(808L));
        newMembership.completeCreation();
        newResult.get(2, TimeUnit.SECONDS);
        assertEquals(Status.SUCCESS, repository().find(808L).status());
    }

    @Test
    void queuedTaskDoesNotCreateChannelAfterGuildLeave() throws Exception {
        GuildFixture fixture = new GuildFixture(809L, true, true);
        MusicCommandChannelProvisioner provisioner = provisioner(new TestChannelState(), 0L);
        CountDownLatch release = new CountDownLatch(1);
        schedulers.getLast().submit(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(provisioner.queueGuildJoinProvisioning(fixture.guild(), null));
        provisioner.guildLeft(809L);
        release.countDown();
        drainScheduler();
        assertNull(repository().find(809L));
        assertEquals(0, fixture.createCalls());
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
        private final AtomicBoolean managePermissions = new AtomicBoolean(true);
        private final boolean autoCompleteCreation;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicInteger createCalls = new AtomicInteger();
        private final AtomicInteger retrieveCalls = new AtomicInteger();
        private final List<Long> createTimestamps = new CopyOnWriteArrayList<>();
        private final CountDownLatch createStarted = new CountDownLatch(1);
        private final Map<Long, TextChannel> cachedChannels = new ConcurrentHashMap<>();
        private final AtomicReference<Consumer<? super TextChannel>> pendingSuccess = new AtomicReference<>();
        private final AtomicReference<Consumer<? super Throwable>> pendingFailure = new AtomicReference<>();
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

        private void failCreation(Throwable failure) {
            pendingFailure.getAndSet(null).accept(failure);
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
                    if (permission == Permission.MANAGE_PERMISSIONS) {
                        return managePermissions.get();
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
                        pendingFailure.set((Consumer<? super Throwable>) args[1]);
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
