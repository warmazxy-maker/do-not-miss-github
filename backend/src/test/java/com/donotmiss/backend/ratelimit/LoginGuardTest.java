package com.donotmiss.backend.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LoginGuardTest {
    private Instant now;
    private LoginGuard guard;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-03T01:00:00Z");
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(() -> now);
        RateLimiter limiter = new RateLimiter(store, ZoneId.of("Asia/Tokyo"), Clock.fixed(now, ZoneOffset.UTC));
        guard = new LoginGuard(store, limiter);
    }

    private void fail(int times, String account, String ip) {
        for (int i = 0; i < times; i++) {
            guard.recordFailure(account, ip);
        }
    }

    @Test
    void fifthWrongPasswordLocksThatAccountFromThatIp() {
        fail(4, "alice", "1.1.1.1");
        RateLimitedException locked = assertThrows(RateLimitedException.class,
                () -> guard.recordFailure("alice", "1.1.1.1"));
        assertEquals(LoginGuard.LOCK.toSeconds(), locked.getRetryAfterSeconds());

        assertThrows(RateLimitedException.class, () -> guard.checkNotLocked("Alice ", "1.1.1.1"));
        // The real owner on another network is not locked out.
        guard.checkNotLocked("alice", "2.2.2.2");

        now = now.plus(LoginGuard.LOCK).plusSeconds(1);
        guard.checkNotLocked("alice", "1.1.1.1");
    }

    @Test
    void successClearsTheCount() {
        fail(4, "alice", "1.1.1.1");
        guard.recordSuccess("alice", "1.1.1.1");
        fail(4, "alice", "1.1.1.1");
        guard.checkNotLocked("alice", "1.1.1.1");
    }

    @Test
    void failuresOlderThanTheWindowAreForgotten() {
        fail(4, "alice", "1.1.1.1");
        now = now.plus(LoginGuard.WINDOW).plusSeconds(1);
        guard.recordFailure("alice", "1.1.1.1");
        guard.checkNotLocked("alice", "1.1.1.1");
    }
}
