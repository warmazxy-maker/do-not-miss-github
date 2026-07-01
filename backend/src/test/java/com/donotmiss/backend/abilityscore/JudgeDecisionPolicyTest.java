package com.donotmiss.backend.abilityscore;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JudgeDecisionPolicyTest {
    private final JudgeDecisionPolicy policy = new JudgeDecisionPolicy();

    @Test
    void highScorePassesWithSmallPositiveConfidenceDelta() {
        JudgeDecisionPolicy.JudgeOutcome outcome = policy.decide(86, true);

        assertEquals(JudgeDecision.PASS, outcome.decision());
        assertEquals(new BigDecimal("0.0360"), outcome.confidenceDelta());
    }

    @Test
    void lowScoreFailsWithSmallNegativeConfidenceDelta() {
        JudgeDecisionPolicy.JudgeOutcome outcome = policy.decide(30, true);

        assertEquals(JudgeDecision.FAIL, outcome.decision());
        assertEquals(new BigDecimal("-0.0400"), outcome.confidenceDelta());
    }

    @Test
    void borderlineScoreRequiresManualReviewWithoutConfidenceChange() {
        JudgeDecisionPolicy.JudgeOutcome outcome = policy.decide(60, true);

        assertEquals(JudgeDecision.MANUAL_REVIEW, outcome.decision());
        assertEquals(BigDecimal.ZERO, outcome.confidenceDelta());
    }

    @Test
    void unavailableModelNeverProducesAutomaticPassOrFail() {
        JudgeDecisionPolicy.JudgeOutcome outcome = policy.decide(100, false);

        assertEquals(JudgeDecision.MANUAL_REVIEW, outcome.decision());
        assertEquals(BigDecimal.ZERO, outcome.confidenceDelta());
    }
}
