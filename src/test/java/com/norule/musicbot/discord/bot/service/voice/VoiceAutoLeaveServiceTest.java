package com.norule.musicbot.discord.bot.service.voice;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.gateway.youtube.YouTubePlaybackRuntimeFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class VoiceAutoLeaveServiceTest {
    @TempDir Path directory;
    private ManualScheduler clock;
    private TestPlayer player;
    private GuildSettingsService settings;
    private VoiceAutoLeaveService service;
    private final Map<Long, Room> rooms = new HashMap<>();
    private JDA jda;
    private final List<com.norule.musicbot.domain.monitoring.VoiceAutoLeaveEvent> events = new ArrayList<>();

    @BeforeEach
    void setup() {
        clock = new ManualScheduler();
        player = new TestPlayer(directory);
        var defaults = new BotConfig("", null, "&", false, null, null, null, null, "en", 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        settings = new GuildSettingsService(directory.resolve("guilds"), defaults);
        service = new VoiceAutoLeaveService(id -> MusicConfig.fromLegacy(settings.getMusic(id), null),
                settings::getLanguage, player, () -> null, clock, events::add);
        jda = stub(JDA.class, call -> switch (call.name) {
            case "getGuilds" -> rooms.values().stream().map(room -> room.guild).toList();
            case "getGuildById" -> rooms.get(((Number) call.args[0]).longValue()).guild;
            default -> null;
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        service.close();
        var field = MusicPlayerService.class.getDeclaredField("playerManager");
        field.setAccessible(true);
        ((AudioPlayerManager) field.get(player)).shutdown();
    }

    private Room room(long id, int humans) {
        Room room = new Room(id, humans);
        rooms.put(id, room);
        return room;
    }

    @Test
    void publishesStructuredEventsWithActualCountsAndKeepsTenSecondDeadline() {
        Room room = room(1, 0);
        room.playing = true;
        service.onConnected(jda);
        var started = events.stream().filter(e -> e.event().equals("TIMER_STARTED")).findFirst().orElseThrow();
        assertEquals("EMPTY_CHANNEL", started.reason());
        assertEquals(1L, started.guildId());
        assertEquals(0L, started.humanCount());
        assertEquals(10L, started.delaySeconds());
        clock.advance(9);
        assertTrue(events.stream().noneMatch(e -> e.event().equals("DISCONNECTED")));
        clock.advance(1);
        var disconnected = events.stream().filter(e -> e.event().equals("DISCONNECTED")).findFirst().orElseThrow();
        assertEquals(started.channelId(), disconnected.channelId());
        assertEquals(0L, disconnected.humanCount());
        assertEquals(0L, disconnected.idleSeconds());
        assertEquals(1, room.leaves);
    }

    @Test
    void returningHumanPublishesCancellationAndPreventsDisconnection() {
        Room room = room(1, 0);
        room.playing = true;
        service.onConnected(jda);
        clock.advance(9);
        room.humans = 1;
        service.onVoiceUpdate(room.guild, false);
        clock.advance(1);
        assertEquals(0, room.leaves);
        assertTrue(events.stream().anyMatch(e -> e.event().equals("TIMER_CANCELLED") && e.reason().equals("USER_JOINED")));
    }

    @Test
    void lastHumanLeavesWhilePlayingAndOtherBotsDoNotCount() {
        Room room = room(1, 1);
        room.playing = true;
        service.onConnected(jda);
        room.humans = 0;
        service.onVoiceUpdate(room.guild, false);
        clock.advance(9);
        assertEquals(0, room.leaves);
        clock.advance(1);
        assertEquals(1, room.leaves);
        assertEquals(1, room.stops);
    }

    @Test
    void humanReturnsAtNineSecondsCancelsEvenAnAlreadyDequeuedCallback() {
        Room room = room(1, 0);
        service.onConnected(jda);
        Task stale = clock.tasks.stream().filter(task -> task.due == 10).findFirst().orElseThrow();
        clock.advance(9);
        room.humans = 1;
        service.onVoiceUpdate(room.guild, false);
        stale.action.run(); // Simulate a cancelled callback that had already begun execution.
        clock.advance(1);
        assertEquals(0, room.leaves);
    }

    @Test
    void joinedWithoutPlaybackLeavesAtTenMinutes() {
        Room room = room(1, 1);
        service.onConnected(jda);
        clock.advance(599);
        assertEquals(0, room.leaves);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void pauseStartsAtPlaybackEventAndResumeCancels() {
        Room room = room(1, 1);
        room.playing = true;
        service.onConnected(jda);
        clock.advance(120);
        room.paused = true;
        player.emit(1, MusicStateChange.PAUSE);
        clock.advance(540);
        room.paused = false;
        player.emit(1, MusicStateChange.RESUME);
        clock.advance(600);
        assertEquals(0, room.leaves);
        room.paused = true;
        player.emit(1, MusicStateChange.PAUSE);
        clock.advance(599);
        assertEquals(0, room.leaves);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void normalPlaybackAndPanelRefreshesNeverCauseIdleLeave() {
        Room room = room(1, 1);
        room.playing = true;
        service.onConnected(jda);
        for (int i = 0; i < 100; i++) {
            clock.advance(20);
            player.emit(1, MusicStateChange.STATE_CHANGED);
        }
        assertEquals(0, room.leaves);
    }

    @Test
    void panelOrVolumeNotificationsDoNotExtendPause() {
        Room room = room(1, 1);
        room.playing = true;
        room.paused = true;
        service.onConnected(jda);
        clock.advance(599);
        player.emit(1, MusicStateChange.VOLUME_CHANGED);
        player.emit(1, MusicStateChange.STATE_CHANGED);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void playbackChangesDoNotExtendEmptyDeadline() {
        Room room = room(1, 0);
        service.onConnected(jda);
        clock.advance(9);
        room.playing = true;
        player.emit(1, MusicStateChange.TRACK_START);
        room.paused = true;
        player.emit(1, MusicStateChange.PAUSE);
        room.transitioning = true;
        player.emit(1, MusicStateChange.RECOVERY);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void loadingAndRecoverySuspendIdleButNotEmptyTimeout() {
        Room room = room(1, 1);
        service.onConnected(jda);
        clock.advance(599);
        room.transitioning = true;
        player.emit(1, MusicStateChange.RECOVERY);
        clock.advance(700);
        assertEquals(0, room.leaves);
        room.transitioning = false;
        player.emit(1, MusicStateChange.STATE_CHANGED);
        clock.advance(599);
        assertEquals(0, room.leaves);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void queueExhaustionStartsCountdownAndSuccessfulPlayCancels() {
        Room room = room(1, 1);
        room.playing = true;
        service.onConnected(jda);
        room.playing = false;
        room.queued = true;
        player.emit(1, MusicStateChange.TRACK_END);
        clock.advance(600);
        assertEquals(0, room.leaves);
        room.queued = false;
        player.emit(1, MusicStateChange.QUEUE_CHANGED);
        clock.advance(599);
        room.playing = true;
        player.emit(1, MusicStateChange.TRACK_START);
        clock.advance(600);
        assertEquals(0, room.leaves);
    }

    @Test
    void moveInvalidatesOldChannelTimerEvenIfCallbackRuns() {
        Room room = room(1, 0);
        service.onConnected(jda);
        Task stale = clock.tasks.stream().filter(task -> task.due == 10).findFirst().orElseThrow();
        clock.advance(9);
        room.channelId = 200;
        service.onVoiceUpdate(room.guild, true);
        stale.action.run();
        clock.advance(9);
        assertEquals(0, room.leaves);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void readyAndGatewayRecoveryImmediatelyInspectCurrentConnections() {
        Room room = room(1, 0);
        service.onConnected(jda);
        clock.advance(9);
        service.onConnected(jda);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void selfJoinIsCheckedWithoutWaitingForPeriodicMonitor() {
        service.onConnected(jda);
        Room room = room(1, 0);
        service.onVoiceUpdate(room.guild, true);
        clock.advance(10);
        assertEquals(1, room.leaves);
    }

    @Test
    void disablingImmediatelyBeforeDeadlinePreventsLeave() {
        Room room = room(1, 0);
        service.onConnected(jda);
        clock.advance(9);
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withAutoLeaveEnabled(false)));
        clock.advance(1);
        assertEquals(0, room.leaves);
    }

    @Test
    void guildTimersAreIndependentAndDisconnectRunsOnlyOnce() {
        Room empty = room(1, 0);
        Room idle = room(2, 1);
        service.onConnected(jda);
        clock.advance(10);
        assertEquals(1, empty.leaves);
        assertEquals(0, idle.leaves);
        player.emit(1, MusicStateChange.STATE_CHANGED);
        service.onVoiceUpdate(empty.guild, false);
        clock.advance(590);
        assertEquals(1, empty.leaves);
        assertEquals(1, idle.leaves);
    }

    @Test
    void deadlineRechecksHumansAndPlaybackEvenWhenEventsWereMissed() {
        Room empty = room(1, 0);
        Room idle = room(2, 1);
        service.onConnected(jda);
        empty.humans = 1;
        idle.playing = true;
        clock.advance(600);
        assertEquals(1, empty.leaves); // Empty deadline cancelled; independent idle timer still applies.
        assertEquals(0, idle.leaves);
    }

    @Test
    void periodicMonitorRepairsAMissedVoiceEvent() {
        Room room = room(1, 1);
        room.playing = true;
        service.onConnected(jda);
        room.humans = 0;
        clock.advance(20);
        clock.monitor.run();
        clock.advance(9);
        assertEquals(0, room.leaves);
        clock.advance(1);
        assertEquals(1, room.leaves);
    }

    @Test
    void briefHumanReturnIsPreservedWhileEventChecksAreQueued() {
        Room room = room(1, 0);
        service.onConnected(jda);
        clock.advance(9);
        clock.defer = true;
        room.humans = 1;
        service.onVoiceUpdate(room.guild, false);
        room.humans = 0;
        service.onVoiceUpdate(room.guild, false);
        clock.drain();
        clock.advance(1);
        assertEquals(0, room.leaves);
        clock.advance(9);
        assertEquals(1, room.leaves);
    }

    @Test
    void simultaneousDeadlinesDisconnectOnlyOnce() {
        Room room = room(1, 1);
        service.onConnected(jda);
        clock.advance(590);
        room.humans = 0;
        service.onVoiceUpdate(room.guild, false);
        clock.advance(10);
        assertEquals(1, room.leaves);
        assertEquals(1, room.stops);
    }

    @Test
    void gatewayRecreationUsesCurrentGuildInsteadOfCapturedOldCache() {
        Room old = room(1, 0);
        service.onConnected(jda);
        clock.advance(9);
        Room refreshed = room(1, 1);
        refreshed.playing = true;
        service.onConnected(jda);
        clock.advance(600);
        assertEquals(0, old.leaves);
        assertEquals(0, refreshed.leaves);
    }

    @Test
    void shutdownCancelsTimersAndDetachesPlaybackSubscriber() {
        Room room = room(1, 0);
        service.onConnected(jda);
        service.close();
        clock.advance(600);
        assertEquals(0, room.leaves);
        assertNull(player.listener);
    }

    private final class Room {
        final Guild guild;
        int humans;
        long channelId = 100;
        boolean connected = true, playing, paused, queued, transitioning;
        int leaves, stops;

        Room(long id, int humans) {
            this.humans = humans;
            User bot = stub(User.class, call -> call.name.equals("isBot") ? true : null);
            User human = stub(User.class, call -> call.name.equals("isBot") ? false : null);
            Member otherBot = stub(Member.class, call -> call.name.equals("getUser") ? bot : null);
            Member humanMember = stub(Member.class, call -> call.name.equals("getUser") ? human : null);
            AudioChannelUnion channel = stub(AudioChannelUnion.class, call -> switch (call.name) {
                case "getIdLong" -> channelId;
                case "getMembers" -> this.humans == 0 ? List.of(otherBot) : List.of(otherBot, humanMember);
                default -> null;
            });
            GuildVoiceState voice = stub(GuildVoiceState.class,
                    call -> call.name.equals("getChannel") && connected ? channel : null);
            SelfMember self = stub(SelfMember.class, call -> switch (call.name) {
                case "getVoiceState" -> voice;
                case "getUser" -> bot;
                default -> null;
            });
            guild = stub(Guild.class, call -> switch (call.name) {
                case "getIdLong" -> id;
                case "getSelfMember" -> self;
                default -> null;
            });
        }
    }

    private final class TestPlayer extends MusicPlayerService {
        BiConsumer<Long, MusicStateChange> listener;
        TestPlayer(Path path) {
            super(path, id -> 50, id -> 365, id -> 100, MusicConfig.defaultValues(), new CipherPolicy(false),
                    auth -> YouTubePlaybackRuntimeFactory.createSource(MusicConfig.defaultValues().getCipher(),
                            new CipherPolicy(false), auth, YouTubePlaybackTrackFactory.youtubeSource()));
        }
        private Room state(Guild guild) { return rooms.get(guild.getIdLong()); }
        @Override public Runnable addStateChangeListener(BiConsumer<Long, MusicStateChange> value) {
            listener = value;
            return () -> listener = null;
        }
        void emit(long id, MusicStateChange change) { listener.accept(id, change); }
        @Override public boolean isPaused(Guild guild) { return state(guild).paused; }
        @Override public String getCurrentTitle(Guild guild) { return state(guild).playing ? "Song" : null; }
        @Override public List<AudioTrack> getQueueSnapshot(Guild guild) {
            return state(guild).queued ? Collections.singletonList(null) : List.of();
        }
        @Override public boolean isPlaybackTransitioning(Guild guild) { return state(guild).transitioning; }
        @Override public void stop(Guild guild) { state(guild).stops++; }
        @Override public void leaveChannel(Guild guild) {
            state(guild).leaves++;
            // Keep voice cache stale to exercise duplicate-disconnect protection.
        }
    }

    private record Call(String name, Object[] args) {}
    private static <T> T stub(Class<T> type, Function<Call, Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) return type.getSimpleName();
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return handler.apply(new Call(method.getName(), args));
        }));
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        long now;
        Runnable monitor;
        boolean defer;
        final List<Runnable> commands = new ArrayList<>();
        final List<Task> tasks = new ArrayList<>();
        ManualScheduler() { super(1); }
        @Override public void execute(Runnable action) {
            if (defer) commands.add(action);
            else action.run();
        }
        void drain() {
            defer = false;
            for (Runnable action : List.copyOf(commands)) action.run();
            commands.clear();
        }
        @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            Task task = new Task(action, now + unit.toSeconds(delay));
            tasks.add(task);
            return task;
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable action, long delay, long period, TimeUnit unit) {
            monitor = action;
            return new Task(action, Long.MAX_VALUE); // Event tests control checks explicitly.
        }
        void advance(long seconds) {
            long target = now + seconds;
            while (true) {
                Task next = tasks.stream().filter(task -> !task.isDone() && task.due <= target)
                        .min(Comparator.comparingLong(task -> task.due)).orElse(null);
                if (next == null) break;
                now = next.due;
                next.run();
            }
            now = target;
        }
    }

    private static final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Runnable action;
        final long due;
        Task(Runnable action, long due) { super(action, null); this.action = action; this.due = due; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(due, TimeUnit.SECONDS); }
        @Override public int compareTo(Delayed other) { return Long.compare(due, ((Task) other).due); }
    }
}
