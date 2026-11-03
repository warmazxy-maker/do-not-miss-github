package com.donotmiss.backend.ratelimit;

import com.donotmiss.backend.common.ApiException;
import org.springframework.http.HttpStatus;

/** 429 with a {@code Retry-After} header, added by GlobalExceptionHandler. */
public class RateLimitedException extends ApiException {
    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
