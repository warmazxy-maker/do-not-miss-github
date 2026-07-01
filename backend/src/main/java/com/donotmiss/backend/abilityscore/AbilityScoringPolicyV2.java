package com.donotmiss.backend.abilityscore;

import java.math.BigDecimal;

public final class AbilityScoringPolicyV2 {
    public static final String RULE_VERSION = "ability-score-v2.0";
    public static final BigDecimal BASE_EXPERIENCE_GAIN = new BigDecimal("12.0000");
    public static final BigDecimal MAX_SINGLE_EXPERIENCE_GAIN = new BigDecimal("20.0000");
    public static final BigDecimal MIN_ABILITY_UNCERTAINTY = new BigDecimal("0.0500");

    public static final BigDecimal COMPLETION_WEIGHT = new BigDecimal("0.30");
    public static final BigDecimal CONTRIBUTION_WEIGHT = new BigDecimal("0.25");
    public static final BigDecimal RELEVANCE_WEIGHT = new BigDecimal("0.30");
    public static final BigDecimal NOVELTY_WEIGHT = new BigDecimal("0.15");

    public static final BigDecimal SOURCE_WEIGHT = new BigDecimal("0.45");
    public static final BigDecimal EVIDENCE_WEIGHT = new BigDecimal("0.30");
    public static final BigDecimal CONTRIBUTION_CONFIDENCE_WEIGHT = new BigDecimal("0.15");
    public static final BigDecimal ASSESSMENT_CONFIDENCE_WEIGHT = new BigDecimal("0.10");

    public static final BigDecimal ABILITY_GAIN_FACTOR = new BigDecimal("0.45");
    public static final BigDecimal UNCERTAINTY_REDUCTION_FACTOR = new BigDecimal("0.12");

    public static final BigDecimal HIGH_IMPACT_GAIN_THRESHOLD = new BigDecimal("16.0000");
    public static final BigDecimal VERIFIED_STATUS_THRESHOLD = new BigDecimal("0.7500");
    public static final BigDecimal LOW_EVIDENCE_THRESHOLD = new BigDecimal("0.4500");
    public static final BigDecimal LOW_SOURCE_THRESHOLD = new BigDecimal("0.5000");
    public static final BigDecimal LOW_PROFILE_CONFIDENCE_THRESHOLD = new BigDecimal("0.4000");
    public static final BigDecimal LOW_ASSESSMENT_CONFIDENCE_THRESHOLD = new BigDecimal("0.5500");
    public static final BigDecimal HIGH_DIFFICULTY_THRESHOLD = new BigDecimal("70.0000");
    public static final BigDecimal VERY_HIGH_DIFFICULTY_THRESHOLD = new BigDecimal("75.0000");
    public static final BigDecimal SCORE_FARMING_NOVELTY_THRESHOLD = new BigDecimal("0.1000");

    private AbilityScoringPolicyV2() {
    }
}
