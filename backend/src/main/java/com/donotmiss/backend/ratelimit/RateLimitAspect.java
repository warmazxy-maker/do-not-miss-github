package com.donotmiss.backend.ratelimit;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Enforces {@link RateLimited}. Ordered inside {@code PermissionAspect} (higher order value = runs
 * later), so the caller is already known to be allowed when we count them.
 */
@Aspect
@Component
@Order(20)
public class RateLimitAspect {
    private final RateLimiter rateLimiter;
    private final CurrentUser currentUser;

    public RateLimitAspect(RateLimiter rateLimiter, CurrentUser currentUser) {
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
    }

    @Around("@annotation(rule)")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimited rule) throws Throwable {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
        if (request == null) {
            return joinPoint.proceed();
        }
        RateLimitTier tier = rule.value();
        String subject = tier.subject() == RateLimitTier.Subject.IP
                ? "ip:" + ClientIp.of(request)
                : currentUser.id(request);
        rateLimiter.consume(tier, subject);
        if (!tier.concurrencyGated()) {
            return joinPoint.proceed();
        }
        try (RateLimiter.Permit ignored = rateLimiter.enter(subject)) {
            return joinPoint.proceed();
        }
    }
}
