package com.donotmiss.backend.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Brute-force protection for login (D4). Wrong passwords are counted per account <em>and</em> IP:
 * locking the account alone would let anyone lock a victim out by typing wrong passwords for them.
 * After {@link #MAX_FAILURES} failures within {@link #WINDOW}, that account + IP is locked for
 * {@link #LOCK}. A successful login clears the count.
 */
@Component
public class LoginGuard {
    private static final Logger log = LoggerFactory.getLogger(LoginGuard.class);
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    static final String LOCK_STAT = "LOGIN_LOCK";

    private final RateLimitStore store;
    private final RateLimiter rateLimiter;

    public LoginGuard(RateLimitStore store, RateLimiter rateLimiter) {
        this.store = store;
        this.rateLimiter = rateLimiter;
    }

    public void checkNotLocked(String account, String ip) {
        long seconds;
        try {
            seconds = store.secondsToLive(lockKey(account, ip));
        } catch (DataAccessException storeDown) {
            log.warn("Rate limit store unavailable, skipping the login lock check.", storeDown);
            return;
        }
        if (seconds > 0) {
            throw locked(seconds);
        }
    }

    /** Counts a wrong password; the failure that reaches the limit is answered with the lock itself. */
    public void recordFailure(String account, String ip) {
        try {
            long failures = store.increment(failureKey(account, ip), WINDOW);
            if (failures >= MAX_FAILURES) {
                store.put(lockKey(account, ip), LOCK);
                store.delete(failureKey(account, ip));
                rateLimiter.recordRejection(LOCK_STAT, "ip:" + ip);
                throw locked(LOCK.toSeconds());
            }
        } catch (DataAccessException storeDown) {
            log.warn("Rate limit store unavailable, login failure not counted.", storeDown);
        }
    }

    public void recordSuccess(String account, String ip) {
        try {
            store.delete(failureKey(account, ip));
        } catch (DataAccessException storeDown) {
            log.warn("Rate limit store unavailable, login failure count not cleared.", storeDown);
        }
    }

    private static RateLimitedException locked(long seconds) {
        return new RateLimitedException("密码连续输错 " + MAX_FAILURES + " 次，请 "
                + Math.max(1, (seconds + 59) / 60) + " 分钟后再试", seconds);
    }

    private static String failureKey(String account, String ip) {
        return "rl:login:fail:" + normalize(account) + "|" + ip;
    }

    private static String lockKey(String account, String ip) {
        return "rl:login:lock:" + normalize(account) + "|" + ip;
    }

    private static String normalize(String account) {
        return account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    }
}
