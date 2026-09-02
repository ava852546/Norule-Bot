package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.shorturl.InMemoryRateLimitStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {
    @Test
    void mediaIpLimitsAreIndependent() {
        MutableClock clock = new MutableClock();
        RateLimitService service = service(clock,
                new RateLimitService.Options(true, 2, 100, 200, 100, 100, 2, 3));

        assertTrue(service.checkMediaUpload("203.0.113.1", "").allowed());
        assertTrue(service.checkMediaUpload("203.0.113.1", "").allowed());
        RateLimitService.Result limited = service.checkMediaUpload("203.0.113.1", "");
        assertFalse(limited.allowed());
        assertTrue(limited.retryAfterSeconds() > 0L);
        assertTrue(service.checkMediaUpload("203.0.113.2", "").allowed());
    }

    @Test
    void authenticatedUsersUseUserLimitBelowTheWiderSharedIpCeiling() {
        MutableClock clock = new MutableClock();
        RateLimitService service = service(clock,
                new RateLimitService.Options(true, 10, 60, 20, 200, 100, 100, 2, 3));

        for (int request = 0; request < 20; request++) {
            assertTrue(service.checkMediaUpload("203.0.113.1", "user-a").allowed());
        }
        assertFalse(service.checkMediaUpload("203.0.113.1", "user-a").allowed());
        assertTrue(service.checkMediaUpload("203.0.113.1", "user-b").allowed());
    }

    @Test
    void anonymousUploadsKeepTheStrictIpLimit() {
        MutableClock clock = new MutableClock();
        RateLimitService service = service(clock,
                new RateLimitService.Options(true, 10, 60, 20, 200, 100, 100, 2, 3));

        for (int request = 0; request < 10; request++) {
            assertTrue(service.checkMediaUpload("203.0.113.1", "").allowed());
        }
        assertFalse(service.checkMediaUpload("203.0.113.1", "").allowed());
    }

    @Test
    void mediaUserMinuteAndDailyLimitsAreIndependent() {
        MutableClock clock = new MutableClock();
        RateLimitService service = service(clock,
                new RateLimitService.Options(true, 100, 2, 3, 100, 100, 2, 3));

        assertTrue(service.checkMediaUpload("203.0.113.1", "user-a").allowed());
        assertTrue(service.checkMediaUpload("203.0.113.2", "user-a").allowed());
        assertFalse(service.checkMediaUpload("203.0.113.3", "user-a").allowed());
        assertTrue(service.checkMediaUpload("203.0.113.1", "user-b").allowed());

        clock.advanceMillis(60_001L);
        assertTrue(service.checkMediaUpload("203.0.113.4", "user-a").allowed());
        assertFalse(service.checkMediaUpload("203.0.113.5", "user-a").allowed());
        assertTrue(service.checkMediaUpload("203.0.113.6", "user-b").allowed());
    }

    @Test
    void shortUrlIpLimitsReturnRetryWindowAndDoNotAffectOtherIps() {
        MutableClock clock = new MutableClock();
        RateLimitService service = service(clock,
                new RateLimitService.Options(true, 100, 100, 200, 2, 100, 2, 3));

        assertTrue(service.checkShortUrlCreation("198.51.100.1", "user-a").allowed());
        assertTrue(service.checkShortUrlCreation("198.51.100.1", "user-a").allowed());
        RateLimitService.Result limited = service.checkShortUrlCreation("198.51.100.1", "user-a");
        assertFalse(limited.allowed());
        assertTrue(limited.retryAfterSeconds() > 0L);
        assertTrue(service.checkShortUrlCreation("198.51.100.2", "user-b").allowed());
    }

    @Test
    void shortUrlUserLimitsAreSharedAcrossIpsAndIndependentBetweenUsers() {
        MutableClock clock = new MutableClock();
        RateLimitService service = service(clock,
                new RateLimitService.Options(true, 100, 100, 200, 100, 2, 2, 3));

        assertTrue(service.checkShortUrlCreation("198.51.100.1", "user-a").allowed());
        assertTrue(service.checkShortUrlCreation("198.51.100.2", "user-a").allowed());
        assertFalse(service.checkShortUrlCreation("198.51.100.3", "user-a").allowed());
        assertTrue(service.checkShortUrlCreation("198.51.100.3", "user-b").allowed());
    }

    @Test
    void uploadConcurrencyPermitsAreReleasedOnEveryClose() {
        MutableClock clock = new MutableClock();
        RateLimitService service = service(clock,
                new RateLimitService.Options(true, 100, 100, 200, 100, 100, 2, 1));

        RateLimitService.UploadPermit first = service.beginMediaUpload("203.0.113.1", "user-a");
        assertTrue(first.allowed());
        RateLimitService.UploadPermit sameUser = service.beginMediaUpload("203.0.113.2", "user-a");
        assertFalse(sameUser.allowed());
        sameUser.close();

        RateLimitService.UploadPermit secondIpSlot = service.beginMediaUpload("203.0.113.1", "user-b");
        assertTrue(secondIpSlot.allowed());
        RateLimitService.UploadPermit thirdIpSlot = service.beginMediaUpload("203.0.113.1", "user-c");
        assertFalse(thirdIpSlot.allowed());

        first.close();
        first.close();
        secondIpSlot.close();
        thirdIpSlot.close();
        try (RateLimitService.UploadPermit reacquired = service.beginMediaUpload(
                "203.0.113.1", "user-a")) {
            assertTrue(reacquired.allowed());
        }
    }

    private RateLimitService service(Clock clock, RateLimitService.Options options) {
        return new RateLimitService(new InMemoryRateLimitStore(), options, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-02T08:00:00Z");

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

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }
    }
}
