package com.donotmiss.backend.abilityscore;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class JudgeDecisionPolicy {
    private static final BigDecimal MAX_DELTA = new BigDecimal("0.0800");

    public JudgeOutcome decide(int totalScore, boolean modelEvaluated) {
        int normalizedScore = Math.min(Math.max(totalScore, 0), 100);
        if (!modelEvaluated) {
            return new JudgeOutcome(JudgeDecision.MANUAL_REVIEW, BigDecimal.ZERO);
        }
        if (normalizedScore >= 70) {
            BigDecimal delta = new BigDecimal("0.0200")
                    .add(BigDecimal.valueOf(normalizedScore - 70)
                            .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP));
            return new JudgeOutcome(JudgeDecision.PASS, delta.min(MAX_DELTA));
        }
        if (normalizedScore < 50) {
            BigDecimal magnitude = new BigDecimal("0.0200")
                    .add(BigDecimal.valueOf(50 - normalizedScore)
                            .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP));
            return new JudgeOutcome(JudgeDecision.FAIL, magnitude.min(MAX_DELTA).negate());
        }
        return new JudgeOutcome(JudgeDecision.MANUAL_REVIEW, BigDecimal.ZERO);
    }

    public record JudgeOutcome(
            JudgeDecision decision,
            BigDecimal confidenceDelta
    ) {
    }
}
