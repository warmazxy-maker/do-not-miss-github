package com.donotmiss.backend.ops;

import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import com.donotmiss.backend.ratelimit.ModelUsageMeter;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.donotmiss.backend.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** D4 card of the ops console: today's model calls and who hit a limit. */
@Service
public class OpsRateLimitService {
    private static final int TOP = 10;
    private static final Map<String, String> EXTRA_LABELS = Map.of(
            "AI_CONCURRENT", "AI 并发闸",
            "LOGIN_LOCK", "登录锁定");

    private final RateLimiter rateLimiter;
    private final ModelUsageMeter usageMeter;
    private final UserRepository userRepository;
    private final long dailyModelBudget;

    public OpsRateLimitService(RateLimiter rateLimiter,
                               ModelUsageMeter usageMeter,
                               UserRepository userRepository,
                               @Value("${app.rate-limit.daily-model-budget:2000}") long dailyModelBudget) {
        this.rateLimiter = rateLimiter;
        this.usageMeter = usageMeter;
        this.userRepository = userRepository;
        this.dailyModelBudget = dailyModelBudget;
    }

    public OpsDtos.RateLimitOverview today() {
        LocalDate day = rateLimiter.today();
        try {
            Map<String, Long> calls = usageMeter.on(day);
            List<OpsDtos.RateLimitCount> rejections = rateLimiter.rejectionsOn(day).entrySet().stream()
                    .map(entry -> new OpsDtos.RateLimitCount(entry.getKey(), label(entry.getKey()), entry.getValue()))
                    .sorted((left, right) -> Long.compare(right.count(), left.count()))
                    .toList();
            List<OpsDtos.RateLimitSubject> most = rateLimiter.mostRejectedOn(day, TOP).stream()
                    .map(entry -> subject(entry.getKey(), entry.getValue()))
                    .toList();
            return new OpsDtos.RateLimitOverview(day.toString(),
                    calls.getOrDefault(ModelUsageMeter.CHAT, 0L),
                    calls.getOrDefault(ModelUsageMeter.EMBEDDING, 0L),
                    dailyModelBudget, rejections, most);
        } catch (DataAccessException redisDown) {
            throw new IllegalStateException("Redis 不可用，读不到限流统计", redisDown);
        }
    }

    private OpsDtos.RateLimitSubject subject(String member, long count) {
        int bar = member.indexOf('|');
        String code = bar < 0 ? member : member.substring(0, bar);
        String raw = bar < 0 ? "" : member.substring(bar + 1);
        String shown = raw.startsWith("ip:")
                ? "IP " + raw.substring(3)
                : userRepository.findByPublicUserId(raw).map(UserEntity::getUsername).orElse(raw);
        return new OpsDtos.RateLimitSubject(code, label(code), shown, count);
    }

    private static String label(String code) {
        if (EXTRA_LABELS.containsKey(code)) {
            return EXTRA_LABELS.get(code);
        }
        try {
            return RateLimitTier.valueOf(code).label();
        } catch (IllegalArgumentException unknown) {
            return code;
        }
    }
}
