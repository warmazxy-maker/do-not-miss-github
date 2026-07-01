package com.donotmiss.backend.abilityscore;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AbilityScoreCalculator {
    private static final int SCALE = 6;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public AbilityScoringModels.ScoreOutput calculate(AbilityScoringModels.ScoreInput rawInput) {
        AbilityScoringModels.ScoreInput input = sanitize(rawInput);

        BigDecimal difficultyDifference = input.activityDifficulty().subtract(input.currentAbilityScore());
        BigDecimal difficultyMultiplier = difficultyMultiplier(difficultyDifference);
        BigDecimal profileMultiplier = profileConfidenceMultiplier(input.profileConfidence());

        BigDecimal growthValue = weightedSum(
                input.completionQuality(), AbilityScoringPolicyV2.COMPLETION_WEIGHT,
                input.personalContribution(), AbilityScoringPolicyV2.CONTRIBUTION_WEIGHT,
                input.dimensionRelevance(), AbilityScoringPolicyV2.RELEVANCE_WEIGHT,
                input.novelty(), AbilityScoringPolicyV2.NOVELTY_WEIGHT
        );

        BigDecimal verificationStrength = weightedSum(
                input.sourceType().credibilityPrior(), AbilityScoringPolicyV2.SOURCE_WEIGHT,
                input.evidenceQuality(), AbilityScoringPolicyV2.EVIDENCE_WEIGHT,
                input.personalContributionConfidence(), AbilityScoringPolicyV2.CONTRIBUTION_CONFIDENCE_WEIGHT,
                input.assessmentConfidence(), AbilityScoringPolicyV2.ASSESSMENT_CONFIDENCE_WEIGHT
        );

        BigDecimal provisionalGain = AbilityScoringPolicyV2.BASE_EXPERIENCE_GAIN
                .multiply(growthValue)
                .multiply(difficultyMultiplier);
        provisionalGain = clamp(
                provisionalGain,
                ZERO,
                AbilityScoringPolicyV2.MAX_SINGLE_EXPERIENCE_GAIN
        );

        BigDecimal verifiedGain = provisionalGain
                .multiply(verificationStrength)
                .multiply(profileMultiplier);
        verifiedGain = clamp(
                verifiedGain,
                ZERO,
                AbilityScoringPolicyV2.MAX_SINGLE_EXPERIENCE_GAIN
        );

        BigDecimal newExperienceScore = input.currentExperienceScore().add(verifiedGain);
        BigDecimal headroom = HUNDRED.subtract(input.currentAbilityScore()).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal challengeSignal = input.activityDifficulty().divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal challengeAdjustment = new BigDecimal("0.50")
                .add(challengeSignal.multiply(new BigDecimal("0.50")));
        BigDecimal abilityGain = verifiedGain
                .multiply(AbilityScoringPolicyV2.ABILITY_GAIN_FACTOR)
                .multiply(headroom)
                .multiply(challengeAdjustment);
        BigDecimal newAbilityScore = clamp(input.currentAbilityScore().add(abilityGain), ZERO, HUNDRED);

        BigDecimal uncertaintyReduction = AbilityScoringPolicyV2.UNCERTAINTY_REDUCTION_FACTOR
                .multiply(verificationStrength)
                .multiply(input.assessmentConfidence())
                .multiply(new BigDecimal("0.50").add(input.novelty().multiply(new BigDecimal("0.50"))));
        BigDecimal newUncertainty = input.currentAbilityUncertainty()
                .multiply(ONE.subtract(clamp(uncertaintyReduction, ZERO, ONE)));
        newUncertainty = clamp(
                newUncertainty,
                AbilityScoringPolicyV2.MIN_ABILITY_UNCERTAINTY,
                ONE
        );

        String oldRank = normalizeRank(input.currentRank(), input.currentAbilityScore());
        String proposedRank = rankFor(newAbilityScore);
        List<String> judgeFlags = judgeFlags(
                input,
                difficultyDifference,
                verifiedGain,
                oldRank,
                proposedRank
        );

        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("sourceType", input.sourceType().name());
        factors.put("sourceCredibilityPrior", scaled(input.sourceType().credibilityPrior()));
        factors.put("activityDifficulty", scaled(input.activityDifficulty()));
        factors.put("completionQuality", scaled(input.completionQuality()));
        factors.put("personalContribution", scaled(input.personalContribution()));
        factors.put("personalContributionConfidence", scaled(input.personalContributionConfidence()));
        factors.put("dimensionRelevance", scaled(input.dimensionRelevance()));
        factors.put("evidenceQuality", scaled(input.evidenceQuality()));
        factors.put("assessmentConfidence", scaled(input.assessmentConfidence()));
        factors.put("novelty", scaled(input.novelty()));
        factors.put("growthValueWeights", Map.of(
                "completion", AbilityScoringPolicyV2.COMPLETION_WEIGHT,
                "contribution", AbilityScoringPolicyV2.CONTRIBUTION_WEIGHT,
                "relevance", AbilityScoringPolicyV2.RELEVANCE_WEIGHT,
                "novelty", AbilityScoringPolicyV2.NOVELTY_WEIGHT
        ));
        factors.put("verificationWeights", Map.of(
                "source", AbilityScoringPolicyV2.SOURCE_WEIGHT,
                "evidence", AbilityScoringPolicyV2.EVIDENCE_WEIGHT,
                "contributionConfidence", AbilityScoringPolicyV2.CONTRIBUTION_CONFIDENCE_WEIGHT,
                "assessmentConfidence", AbilityScoringPolicyV2.ASSESSMENT_CONFIDENCE_WEIGHT
        ));
        factors.put("baseExperienceGain", AbilityScoringPolicyV2.BASE_EXPERIENCE_GAIN);
        factors.put("abilityGainFactor", AbilityScoringPolicyV2.ABILITY_GAIN_FACTOR);
        factors.put("uncertaintyReductionFactor", AbilityScoringPolicyV2.UNCERTAINTY_REDUCTION_FACTOR);

        return new AbilityScoringModels.ScoreOutput(
                scaled(input.currentExperienceScore()),
                scaled(provisionalGain),
                scaled(verifiedGain),
                scaled(newExperienceScore),
                scaled(input.currentAbilityScore()),
                scaled(newAbilityScore),
                scaled(input.currentAbilityUncertainty()),
                scaled(newUncertainty),
                scaled(growthValue),
                scaled(verificationStrength),
                scaled(difficultyDifference),
                scaled(difficultyMultiplier),
                scaled(profileMultiplier),
                scaled(abilityGain),
                oldRank,
                proposedRank,
                List.copyOf(judgeFlags),
                Map.copyOf(factors),
                AbilityScoringPolicyV2.RULE_VERSION
        );
    }

    public BigDecimal difficultyMultiplier(BigDecimal difficultyDifference) {
        BigDecimal difference = valueOrZero(difficultyDifference);
        if (difference.compareTo(new BigDecimal("-40")) <= 0) {
            return new BigDecimal("0.05");
        }
        if (difference.compareTo(new BigDecimal("-20")) <= 0) {
            return new BigDecimal("0.20");
        }
        if (difference.compareTo(new BigDecimal("-5")) <= 0) {
            return new BigDecimal("0.50");
        }
        if (difference.compareTo(new BigDecimal("10")) <= 0) {
            return new BigDecimal("1.00");
        }
        if (difference.compareTo(new BigDecimal("25")) <= 0) {
            return new BigDecimal("1.20");
        }
        if (difference.compareTo(new BigDecimal("40")) <= 0) {
            return new BigDecimal("0.75");
        }
        return new BigDecimal("0.30");
    }

    public BigDecimal profileConfidenceMultiplier(BigDecimal profileConfidence) {
        BigDecimal confidence = clamp(valueOrDefault(profileConfidence, new BigDecimal("0.50")), ZERO, ONE);
        if (confidence.compareTo(new BigDecimal("0.20")) <= 0) {
            return new BigDecimal("0.85");
        }
        if (confidence.compareTo(new BigDecimal("0.40")) <= 0) {
            return new BigDecimal("0.92");
        }
        if (confidence.compareTo(new BigDecimal("0.60")) <= 0) {
            return new BigDecimal("1.00");
        }
        if (confidence.compareTo(new BigDecimal("0.80")) <= 0) {
            return new BigDecimal("1.05");
        }
        return new BigDecimal("1.10");
    }

    public String rankFor(BigDecimal abilityScore) {
        BigDecimal score = clamp(valueOrZero(abilityScore), ZERO, HUNDRED);
        if (score.compareTo(new BigDecimal("10")) < 0) {
            return "UNRATED";
        }
        if (score.compareTo(new BigDecimal("30")) < 0) {
            return "FOUNDATION";
        }
        if (score.compareTo(new BigDecimal("50")) < 0) {
            return "DEVELOPING";
        }
        if (score.compareTo(new BigDecimal("70")) < 0) {
            return "PROFICIENT";
        }
        if (score.compareTo(new BigDecimal("85")) < 0) {
            return "ADVANCED";
        }
        return "EXPERT";
    }

    private AbilityScoringModels.ScoreInput sanitize(AbilityScoringModels.ScoreInput input) {
        if (input == null) {
            throw new IllegalArgumentException("评分输入不能为空");
        }
        return new AbilityScoringModels.ScoreInput(
                nonNegative(input.currentExperienceScore()),
                clamp(valueOrZero(input.currentAbilityScore()), ZERO, HUNDRED),
                clamp(valueOrDefault(input.currentAbilityUncertainty(), ONE), ZERO, ONE),
                input.currentRank(),
                clamp(valueOrDefault(input.profileConfidence(), new BigDecimal("0.50")), ZERO, ONE),
                clamp(valueOrZero(input.activityDifficulty()), ZERO, HUNDRED),
                unit(input.completionQuality()),
                unit(input.personalContribution()),
                unit(input.personalContributionConfidence()),
                unit(input.dimensionRelevance()),
                unit(input.evidenceQuality()),
                unit(input.assessmentConfidence()),
                unit(input.novelty()),
                input.sourceType() == null ? AbilityEvidenceSourceType.OTHER : input.sourceType()
        );
    }

    private List<String> judgeFlags(AbilityScoringModels.ScoreInput input,
                                    BigDecimal difficultyDifference,
                                    BigDecimal verifiedGain,
                                    String oldRank,
                                    String proposedRank) {
        List<String> flags = new ArrayList<>();
        if (difficultyDifference.compareTo(new BigDecimal("40")) > 0) {
            flags.add("DIFFICULTY_GAP_OVER_40");
        }
        if (verifiedGain.compareTo(AbilityScoringPolicyV2.HIGH_IMPACT_GAIN_THRESHOLD) >= 0) {
            flags.add("HIGH_SINGLE_ACTIVITY_GAIN");
        }
        if (input.evidenceQuality().compareTo(AbilityScoringPolicyV2.LOW_EVIDENCE_THRESHOLD) < 0
                && input.activityDifficulty().compareTo(AbilityScoringPolicyV2.HIGH_DIFFICULTY_THRESHOLD) >= 0) {
            flags.add("HIGH_DIFFICULTY_WITH_WEAK_EVIDENCE");
        }
        if (input.sourceType().credibilityPrior().compareTo(AbilityScoringPolicyV2.LOW_SOURCE_THRESHOLD) < 0
                && input.activityDifficulty().compareTo(AbilityScoringPolicyV2.HIGH_DIFFICULTY_THRESHOLD) >= 0) {
            flags.add("STRONG_CLAIM_WITH_LOW_SOURCE_VERIFIABILITY");
        }
        if (input.novelty().compareTo(AbilityScoringPolicyV2.SCORE_FARMING_NOVELTY_THRESHOLD) <= 0) {
            flags.add("POSSIBLE_SCORE_FARMING");
        }
        if (!oldRank.equals(proposedRank) && rankOrder(proposedRank) >= rankOrder("DEVELOPING")) {
            flags.add("RANK_PROMOTION_REQUIRES_JUDGE");
        }
        if (input.profileConfidence().compareTo(AbilityScoringPolicyV2.LOW_PROFILE_CONFIDENCE_THRESHOLD) < 0
                && input.activityDifficulty().compareTo(AbilityScoringPolicyV2.VERY_HIGH_DIFFICULTY_THRESHOLD) >= 0) {
            flags.add("LOW_CONFIDENCE_WITH_STRONG_CLAIM");
        }
        if (input.assessmentConfidence().compareTo(AbilityScoringPolicyV2.LOW_ASSESSMENT_CONFIDENCE_THRESHOLD) < 0
                && verifiedGain.compareTo(new BigDecimal("8.0000")) >= 0) {
            flags.add("LOW_EXTRACTION_CONFIDENCE_FOR_HIGH_IMPACT_RESULT");
        }
        return flags;
    }

    private int rankOrder(String rank) {
        return switch (rank) {
            case "FOUNDATION" -> 1;
            case "DEVELOPING" -> 2;
            case "PROFICIENT" -> 3;
            case "ADVANCED" -> 4;
            case "EXPERT" -> 5;
            default -> 0;
        };
    }

    private String normalizeRank(String currentRank, BigDecimal currentAbilityScore) {
        if (currentRank == null || currentRank.isBlank()) {
            return rankFor(currentAbilityScore);
        }
        return currentRank.trim().toUpperCase();
    }

    private BigDecimal weightedSum(BigDecimal value1, BigDecimal weight1,
                                   BigDecimal value2, BigDecimal weight2,
                                   BigDecimal value3, BigDecimal weight3,
                                   BigDecimal value4, BigDecimal weight4) {
        return scaled(value1.multiply(weight1)
                .add(value2.multiply(weight2))
                .add(value3.multiply(weight3))
                .add(value4.multiply(weight4)));
    }

    private BigDecimal unit(BigDecimal value) {
        return clamp(valueOrZero(value), ZERO, ONE);
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return valueOrZero(value).max(ZERO);
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal valueOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.max(min).min(max);
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
