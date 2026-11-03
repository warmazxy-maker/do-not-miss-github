package com.donotmiss.backend.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The few counter operations rate limiting needs. Redis in production ({@link RedisRateLimitStore});
 * tests use an in-memory version so the rules can be checked without a server.
 *
 * <p>Implementations may throw {@link org.springframework.dao.DataAccessException} when the store
 * is down; callers decide what that means (D4: let the request through).</p>
 */
public interface RateLimitStore {
    /** Adds one and returns the new count; the expiry is set when the key is created. */
    long increment(String key, Duration ttl);

    /** Takes a slot if fewer than {@code max} are taken; the expiry is refreshed on every attempt. */
    boolean acquire(String key, int max, Duration ttl);

    void release(String key);

    /** Seconds until the key expires, or 0 when it does not exist. */
    long secondsToLive(String key);

    void put(String key, Duration ttl);

    void delete(String key);

    void incrementField(String key, String field, Duration ttl);

    Map<String, Long> fields(String key);

    void incrementScore(String key, String member, Duration ttl);

    /** Highest scores first. */
    List<Map.Entry<String, Long>> top(String key, int limit);
}
