package com.donotmiss.backend.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Fixed-window counting and the AI concurrency gate (D4).
 *
 * <p><b>Fixed window</b>: time is cut into whole minutes / hours / local days; each window has its
 * own counter in Redis, named after the window, which expires when the window ends. A request adds
 * one; above the limit it is refused until the next window starts.</p>
 *
 * <p><b>Concurrency gate</b>: counts the caller's AI requests that have started and not finished.
 * It stops one person holding many server threads while each waits up to 30 s for the model.</p>
 *
 * <p><b>Redis down</b>: the request goes through and a warning is logged. Logins are stored in Redis
 * too, so in that case the site is unusable anyway; refusing everything here would add nothing.</p>
 */
@Service
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    static final int MAX_IN_FLIGHT = 2;
    /** Longer than the slowest AI request (action plans run several 30 s calls in a row). */
    static final Duration IN_FLIGHT_TTL = Duration.ofMinutes(5);
    static final Duration STATS_TTL = Duration.ofDays(8);
    static final String CONCURRENCY_STAT = "AI_CONCURRENT";

    private final RateLimitStore store;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public RateLimiter(RateLimitStore store, @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this(store, ZoneId.of(timezone), Clock.systemUTC());
    }

    RateLimiter(RateLimitStore store, ZoneId zone, Clock clock) {
        this.store = store;
        this.zone = zone;
        this.clock = clock;
    }

    /** Counts one request of {@code subject} in every window of the tier; 429 when one is full. */
    public void consume(RateLimitTier tier, String subject) {
        Instant now = clock.instant();
        long localSeconds = now.getEpochSecond() + zone.getRules().getOffset(now).getTotalSeconds();
        for (RateLimitTier.Window window : tier.windows()) {
            long length = window.length().toSeconds();
            long bucket = localSeconds / length;
            long secondsLeft = length - localSeconds % length;
            String key = "rl:" + tier.name() + ":" + window.code() + ":" + subject + ":" + bucket;
            long count;
            try {
                count = store.increment(key, Duration.ofSeconds(secondsLeft + 5));
            } catch (DataAccessException storeDown) {
                log.warn("Rate limit store unavailable, letting the request through. tier={}", tier, storeDown);
                return;
            }
            if (count > window.limit()) {
                recordRejection(tier.name(), subject);
                throw new RateLimitedException(message(tier, window, secondsLeft), secondsLeft);
            }
        }
    }

    /**
     * Takes one of the caller's AI slots. Close the returned permit when the request ends, whether
     * it succeeded or failed; a permit left open by a crash expires after {@link #IN_FLIGHT_TTL}.
     */
    public Permit enter(String userId) {
        String key = "rl:inflight:" + userId;
        try {
            if (!store.acquire(key, MAX_IN_FLIGHT, IN_FLIGHT_TTL)) {
                recordRejection(CONCURRENCY_STAT, userId);
                throw new RateLimitedException("你有 " + MAX_IN_FLIGHT + " 个 AI 请求还在处理，请等它们完成后再试", 5);
            }
        } catch (DataAccessException storeDown) {
            log.warn("Rate limit store unavailable, skipping the concurrency gate.", storeDown);
            return () -> { };
        }
        return () -> {
            try {
                store.release(key);
            } catch (DataAccessException storeDown) {
                log.warn("Could not release an AI slot; it expires on its own.", storeDown);
            }
        };
    }

    void recordRejection(String statName, String subject) {
        String day = "rl:stats:" + today();
        try {
            store.incrementField(day, statName, STATS_TTL);
            store.incrementScore(day + ":subjects", statName + "|" + subject, STATS_TTL);
        } catch (DataAccessException storeDown) {
            log.warn("Could not record a rate-limit rejection.", storeDown);
        }
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /** Refusals of the day per tier name (plus {@code AI_CONCURRENT} and {@code LOGIN_LOCK}). */
    public Map<String, Long> rejectionsOn(LocalDate day) {
        return store.fields("rl:stats:" + day);
    }

    /** Most refused "tier|subject" pairs of the day. */
    public List<Map.Entry<String, Long>> mostRejectedOn(LocalDate day, int limit) {
        return store.top("rl:stats:" + day + ":subjects", limit);
    }

    static String message(RateLimitTier tier, RateLimitTier.Window window, long secondsLeft) {
        return switch (window.code()) {
            case "d" -> "今天的" + tier.label() + "次数已用完（每天最多 " + window.limit() + " 次），明天再来吧";
            case "h" -> tier.label() + "太频繁（每小时最多 " + window.limit() + " 次），请 "
                    + Math.max(1, (secondsLeft + 59) / 60) + " 分钟后再试";
            default -> tier.label() + "太频繁（每分钟最多 " + window.limit() + " 次），请 " + secondsLeft + " 秒后再试";
        };
    }

    /** One AI slot; closing it frees the slot. */
    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
