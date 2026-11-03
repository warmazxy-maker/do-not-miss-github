package com.donotmiss.backend.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The counters live in Redis so every backend instance sees the same numbers. The two operations
 * that read and write in one step are Lua scripts: Redis runs a script without interleaving other
 * commands, so "add one, and set the expiry if this is a new key" cannot be split by a crash or by
 * a second request.
 */
@Component
public class RedisRateLimitStore implements RateLimitStore {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            if count > tonumber(ARGV[1]) then
              redis.call('DECR', KEYS[1])
              return 0
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local count = redis.call('DECR', KEYS[1])
            if count <= 0 then redis.call('DEL', KEYS[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long count = redis.execute(INCREMENT, List.of(key), String.valueOf(ttl.toSeconds()));
        return count == null ? 0 : count;
    }

    @Override
    public boolean acquire(String key, int max, Duration ttl) {
        Long taken = redis.execute(ACQUIRE, List.of(key), String.valueOf(max), String.valueOf(ttl.toSeconds()));
        return taken != null && taken == 1;
    }

    @Override
    public void release(String key) {
        redis.execute(RELEASE, List.of(key));
    }

    @Override
    public long secondsToLive(String key) {
        Long seconds = redis.getExpire(key);
        return seconds == null || seconds < 0 ? 0 : seconds;
    }

    @Override
    public void put(String key, Duration ttl) {
        redis.opsForValue().set(key, "1", ttl);
    }

    @Override
    public void delete(String key) {
        redis.delete(key);
    }

    @Override
    public void incrementField(String key, String field, Duration ttl) {
        redis.opsForHash().increment(key, field, 1);
        redis.expire(key, ttl);
    }

    @Override
    public Map<String, Long> fields(String key) {
        Map<String, Long> result = new LinkedHashMap<>();
        redis.opsForHash().entries(key).forEach((field, value) ->
                result.put(String.valueOf(field), Long.parseLong(String.valueOf(value))));
        return result;
    }

    @Override
    public void incrementScore(String key, String member, Duration ttl) {
        redis.opsForZSet().incrementScore(key, member, 1);
        redis.expire(key, ttl);
    }

    @Override
    public List<Map.Entry<String, Long>> top(String key, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);
        List<Map.Entry<String, Long>> result = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                long score = tuple.getScore() == null ? 0 : tuple.getScore().longValue();
                result.add(new AbstractMap.SimpleEntry<>(tuple.getValue(), score));
            }
        }
        return result;
    }
}
