package com.donotmiss.backend.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterTest {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private Instant now;
    private InMemoryRateLimitStore store;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        // 10:00:30 in Tokyo.
        now = Instant.parse("2026-11-03T01:00:30Z");
        store = new InMemoryRateLimitStore(() -> now);
        Clock clock = new Clock() {
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
                return now;
            }
        };
        limiter = new RateLimiter(store, TOKYO, clock);
    }

    @Test
    void perMinuteLimitRefusesWithTheSecondsLeftAndResetsNextMinute() {
        for (int i = 0; i < 3; i++) {
            limiter.consume(RateLimitTier.AI_HEAVY, "stu-1");
        }
        RateLimitedException refused = assertThrows(RateLimitedException.class,
                () -> limiter.consume(RateLimitTier.AI_HEAVY, "stu-1"));
        assertEquals(30, refused.getRetryAfterSeconds());
        assertTrue(refused.getMessage().contains("30 秒后"));

        limiter.consume(RateLimitTier.AI_HEAVY, "stu-2");

        now = now.plusSeconds(30);
        limiter.consume(RateLimitTier.AI_HEAVY, "stu-1");
    }

    @Test
    void aMinuteRefusalDoesNotEatTheDailyQuota() {
        // 3 per minute, 30 per day: hammering inside one minute must not use up the day.
        for (int i = 0; i < 50; i++) {
            try {
                limiter.consume(RateLimitTier.AI_HEAVY, "stu-1");
            } catch (RateLimitedException expected) {
                // per-minute refusal
            }
        }
        for (int minute = 1; minute <= 9; minute++) {
            now = now.plus(Duration.ofMinutes(1));
            for (int i = 0; i < 3; i++) {
                limiter.consume(RateLimitTier.AI_HEAVY, "stu-1");
            }
        }
        now = now.plus(Duration.ofMinutes(1));
        RateLimitedException refused = assertThrows(RateLimitedException.class,
                () -> limiter.consume(RateLimitTier.AI_HEAVY, "stu-1"));
        assertTrue(refused.getMessage().contains("今天"));
        // Until midnight in Tokyo: 10:10:30 → 24:00.
        assertEquals(Duration.ofHours(13).plusMinutes(49).plusSeconds(30).toSeconds(), refused.getRetryAfterSeconds());
    }

    @Test
    void theDayFollowsTheConfiguredZoneNotUtc() {
        // 23:59:50 in Tokyo is still the same UTC day, but a new local day starts ten seconds later.
        now = Instant.parse("2026-11-03T14:59:50Z");
        for (int i = 0; i < 20; i++) {
            limiter.consume(RateLimitTier.EVENT_SUBMIT, "org-1");
        }
        assertThrows(RateLimitedException.class, () -> limiter.consume(RateLimitTier.EVENT_SUBMIT, "org-1"));
        now = now.plusSeconds(10);
        limiter.consume(RateLimitTier.EVENT_SUBMIT, "org-1");
    }

    @Test
    void concurrencyGateHoldsTwoAndFreesOnClose() {
        RateLimiter.Permit first = limiter.enter("stu-1");
        RateLimiter.Permit second = limiter.enter("stu-1");
        assertThrows(RateLimitedException.class, () -> limiter.enter("stu-1"));
        limiter.enter("stu-2").close();

        first.close();
        limiter.enter("stu-1").close();
        second.close();
        assertEquals(0, store.current("rl:inflight:stu-1"));
    }

    @Test
    void aSlotLeftOpenByACrashExpires() {
        limiter.enter("stu-1");
        limiter.enter("stu-1");
        now = now.plus(RateLimiter.IN_FLIGHT_TTL).plusSeconds(1);
        limiter.enter("stu-1").close();
    }

    @Test
    void refusalsAreCountedForTheOpsConsole() {
        for (int i = 0; i < 5; i++) {
            try {
                limiter.consume(RateLimitTier.AI_HEAVY, "stu-1");
            } catch (RateLimitedException expected) {
                // two refusals
            }
        }
        assertEquals(2L, limiter.rejectionsOn(limiter.today()).get("AI_HEAVY"));
        assertEquals("AI_HEAVY|stu-1", limiter.mostRejectedOn(limiter.today(), 5).getFirst().getKey());
    }

    @Test
    void storeDownLetsRequestsThrough() {
        RateLimitStore down = mock(RateLimitStore.class);
        when(down.increment(any(), any())).thenThrow(new DataAccessResourceFailureException("redis down"));
        when(down.acquire(any(), anyInt(), any())).thenThrow(new DataAccessResourceFailureException("redis down"));
        RateLimiter failOpen = new RateLimiter(down, TOKYO, Clock.systemUTC());

        for (int i = 0; i < 10; i++) {
            failOpen.consume(RateLimitTier.AI_HEAVY, "stu-1");
            failOpen.enter("stu-1").close();
        }
    }
}
