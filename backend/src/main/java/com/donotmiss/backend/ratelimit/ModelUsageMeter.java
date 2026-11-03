package com.donotmiss.backend.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * Counts every request actually sent to the model, per local day (D4 total budget). It only
 * counts: nothing is paused when the budget is exceeded, the ops console just shows it.
 */
@Component
public class ModelUsageMeter {
    private static final Logger log = LoggerFactory.getLogger(ModelUsageMeter.class);
    public static final String CHAT = "chat";
    public static final String EMBEDDING = "embedding";

    private final RateLimitStore store;
    private final RateLimiter rateLimiter;

    public ModelUsageMeter(RateLimitStore store, RateLimiter rateLimiter) {
        this.store = store;
        this.rateLimiter = rateLimiter;
    }

    public void record(String kind) {
        try {
            store.incrementField(key(rateLimiter.today()), kind, RateLimiter.STATS_TTL);
        } catch (DataAccessException storeDown) {
            log.debug("Model usage not counted, store unavailable: {}", storeDown.getMessage());
        }
    }

    public Map<String, Long> on(LocalDate day) {
        return store.fields(key(day));
    }

    private static String key(LocalDate day) {
        return "ai:calls:" + day;
    }
}
