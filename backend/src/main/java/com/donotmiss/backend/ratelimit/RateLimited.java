package com.donotmiss.backend.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts an endpoint into a {@link RateLimitTier}. Enforced by {@link RateLimitAspect}, which runs
 * after the permission check, so a caller without permission gets 403, not 429.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {
    RateLimitTier value();
}
