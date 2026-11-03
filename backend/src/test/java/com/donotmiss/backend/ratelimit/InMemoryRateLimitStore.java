package com.donotmiss.backend.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Same behaviour as the Redis store, expiry included, driven by a test clock. */
class InMemoryRateLimitStore implements RateLimitStore {
    private final Supplier<Instant> now;
    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, Instant> expiries = new HashMap<>();
    private final Map<String, Map<String, Long>> hashes = new HashMap<>();

    InMemoryRateLimitStore(Supplier<Instant> now) {
        this.now = now;
    }

    private void evict(String key) {
        Instant expiry = expiries.get(key);
        if (expiry != null && !expiry.isAfter(now.get())) {
            counters.remove(key);
            expiries.remove(key);
        }
    }

    @Override
    public long increment(String key, Duration ttl) {
        evict(key);
        long count = counters.merge(key, 1L, Long::sum);
        if (count == 1) {
            expiries.put(key, now.get().plus(ttl));
        }
        return count;
    }

    @Override
    public boolean acquire(String key, int max, Duration ttl) {
        evict(key);
        long count = counters.merge(key, 1L, Long::sum);
        expiries.put(key, now.get().plus(ttl));
        if (count > max) {
            counters.merge(key, -1L, Long::sum);
            return false;
        }
        return true;
    }

    @Override
    public void release(String key) {
        long count = counters.merge(key, -1L, Long::sum);
        if (count <= 0) {
            counters.remove(key);
            expiries.remove(key);
        }
    }

    long current(String key) {
        evict(key);
        return counters.getOrDefault(key, 0L);
    }

    @Override
    public long secondsToLive(String key) {
        evict(key);
        Instant expiry = expiries.get(key);
        return expiry == null ? 0 : Duration.between(now.get(), expiry).toSeconds();
    }

    @Override
    public void put(String key, Duration ttl) {
        counters.put(key, 1L);
        expiries.put(key, now.get().plus(ttl));
    }

    @Override
    public void delete(String key) {
        counters.remove(key);
        expiries.remove(key);
    }

    @Override
    public void incrementField(String key, String field, Duration ttl) {
        hashes.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(field, 1L, Long::sum);
    }

    @Override
    public Map<String, Long> fields(String key) {
        return hashes.getOrDefault(key, Map.of());
    }

    @Override
    public void incrementScore(String key, String member, Duration ttl) {
        incrementField(key, member, ttl);
    }

    @Override
    public List<Map.Entry<String, Long>> top(String key, int limit) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(fields(key).entrySet());
        entries.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        return entries.subList(0, Math.min(limit, entries.size()));
    }
}
