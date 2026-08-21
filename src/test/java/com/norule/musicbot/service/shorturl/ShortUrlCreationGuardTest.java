package com.norule.musicbot.service.shorturl;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlCreationGuardTest {
    @Test
    void limitsAnonymousRequestsPerIpAndReturnsRetryAfter() {
        ShortUrlCreationGuard guard = new ShortUrlCreationGuard(
                new ShortUrlCreationGuard.Options(true, 10, 50, 200, 30, 150, 500));

        for (int i = 0; i < 10; i++) {
            assertTrue(guard.checkRequest("", "198.51.100.10").allowed());
        }
        ShortUrlCreationGuard.Decision denied = guard.checkRequest("", "198.51.100.10");

        assertEquals(ShortUrlCreationGuard.Status.REQUEST_RATE_LIMITED, denied.status());
        assertTrue(denied.retryAfterSeconds() > 0L);
        assertTrue(guard.checkRequest("", "198.51.100.11").allowed());
    }

    @Test
    void keepsAuthenticatedAndAnonymousSubjectsSeparate() {
        ShortUrlCreationGuard guard = new ShortUrlCreationGuard(
                new ShortUrlCreationGuard.Options(true, 1, 1, 1, 1, 1, 1));

        assertTrue(guard.checkRequest("", "198.51.100.10").allowed());
        assertEquals(ShortUrlCreationGuard.Status.REQUEST_RATE_LIMITED,
                guard.checkRequest("", "198.51.100.10").status());
        assertTrue(guard.checkRequest("discord-user-a", "198.51.100.10").allowed());
    }

    @Test
    void enforcesTheTenMinuteRequestWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T12:00:00Z"));
        ShortUrlCreationGuard guard = new ShortUrlCreationGuard(
                new ShortUrlCreationGuard.Options(true, 2, 3, 10, 2, 3, 10), clock);

        assertTrue(guard.checkRequest("", "198.51.100.10").allowed());
        assertTrue(guard.checkRequest("", "198.51.100.10").allowed());
        clock.advanceSeconds(61L);
        assertTrue(guard.checkRequest("", "198.51.100.10").allowed());
        assertEquals(ShortUrlCreationGuard.Status.REQUEST_RATE_LIMITED,
                guard.checkRequest("", "198.51.100.10").status());
    }

    @Test
    void failedCreationDoesNotConsumeDailyQuotaButSuccessfulCreationDoes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T12:00:00Z"));
        ShortUrlCreationGuard guard = new ShortUrlCreationGuard(
                new ShortUrlCreationGuard.Options(true, 10, 50, 1, 30, 150, 1), clock);

        try (ShortUrlCreationGuard.CreationPermit invalidRequest = guard.beginCreation("", "198.51.100.10")) {
            assertTrue(invalidRequest.allowed());
        }
        try (ShortUrlCreationGuard.CreationPermit successful = guard.beginCreation("", "198.51.100.10")) {
            assertTrue(successful.allowed());
            successful.commitSuccessfulCreation();
        }
        try (ShortUrlCreationGuard.CreationPermit denied = guard.beginCreation("", "198.51.100.10")) {
            assertEquals(ShortUrlCreationGuard.Status.DAILY_QUOTA_EXCEEDED, denied.status());
            assertTrue(denied.retryAfterSeconds() > 0L);
        }
    }

    @Test
    void concurrentReservationsCannotExceedDailyQuota() throws Exception {
        int dailyQuota = 5;
        ShortUrlCreationGuard guard = new ShortUrlCreationGuard(
                new ShortUrlCreationGuard.Options(true, 100, 100, dailyQuota, 100, 100, dailyQuota));
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                tasks.add(() -> {
                    try (ShortUrlCreationGuard.CreationPermit permit = guard.beginCreation(
                            "discord-user-a", "198.51.100.10")) {
                        if (!permit.allowed()) {
                            return false;
                        }
                        permit.commitSuccessfulCreation();
                        return true;
                    }
                });
            }
            int allowed = 0;
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                if (result.get()) {
                    allowed++;
                }
            }
            assertEquals(dailyQuota, allowed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void negativeConfigurationCannotDisableLimitsAccidentally() {
        ShortUrlCreationGuard.Options options = new ShortUrlCreationGuard.Options(
                true, -1, -2, -3, -4, -5, -6);

        assertEquals(1, options.anonymousPerMinute());
        assertEquals(1, options.anonymousPerTenMinutes());
        assertEquals(1, options.anonymousDailyCreates());
        assertEquals(1, options.authenticatedPerMinute());
        assertEquals(1, options.authenticatedPerTenMinutes());
        assertEquals(1, options.authenticatedDailyCreates());
    }

    @Test
    void dailyQuotaIsProcessLocalAndResetsWithANewGuardInstance() {
        ShortUrlCreationGuard.Options options = new ShortUrlCreationGuard.Options(
                true, 10, 50, 1, 30, 150, 1);
        ShortUrlCreationGuard firstProcess = new ShortUrlCreationGuard(options);
        try (ShortUrlCreationGuard.CreationPermit permit = firstProcess.beginCreation("", "198.51.100.10")) {
            permit.commitSuccessfulCreation();
        }
        assertEquals(ShortUrlCreationGuard.Status.DAILY_QUOTA_EXCEEDED,
                firstProcess.beginCreation("", "198.51.100.10").status());

        ShortUrlCreationGuard restartedProcess = new ShortUrlCreationGuard(options);

        assertTrue(restartedProcess.beginCreation("", "198.51.100.10").allowed());
    }

    @Test
    void boundsTrackedIdentities() {
        ShortUrlCreationGuard guard = new ShortUrlCreationGuard(
                new ShortUrlCreationGuard.Options(true, 10, 50, 10, 30, 150, 10));
        for (int i = 0; i < ShortUrlCreationGuard.MAX_TRACKED_IDENTITIES; i++) {
            assertTrue(guard.checkRequest("", "198.51." + (i / 256) + "." + (i % 256)).allowed());
        }

        ShortUrlCreationGuard.Decision overflow = guard.checkRequest("", "203.0.113.250");

        assertEquals(ShortUrlCreationGuard.Status.TRACKING_CAPACITY_EXCEEDED, overflow.status());
        assertEquals(ShortUrlCreationGuard.MAX_TRACKED_IDENTITIES, guard.trackedIdentityCount());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
