package com.donotmiss.backend.abilityscore;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class AbilityScoringModels {
    private AbilityScoringModels() {
    }

    public record ScoreInput(
            BigDecimal currentExperienceScore,
            BigDecimal currentAbilityScore,
            BigDecimal currentAbilityUncertainty,
            String currentRank,
            BigDecimal profileConfidence,
            BigDecimal activityDifficulty,
            BigDecimal completionQuality,
            BigDecimal personalContribution,
            BigDecimal personalContributionConfidence,
            BigDecimal dimensionRelevance,
            BigDecimal evidenceQuality,
            BigDecimal assessmentConfidence,
            BigDecimal novelty,
            AbilityEvidenceSourceType sourceType
    ) {
    }

    public record ScoreOutput(
            BigDecimal oldExperienceScore,
            BigDecimal provisionalExperienceGain,
            BigDecimal verifiedExperienceGain,
            BigDecimal newExperienceScore,
            BigDecimal oldAbilityScore,
            BigDecimal newAbilityScore,
            BigDecimal oldAbilityUncertainty,
            BigDecimal newAbilityUncertainty,
            BigDecimal growthValue,
            BigDecimal verificationStrength,
            BigDecimal difficultyDifference,
            BigDecimal difficultyMatchMultiplier,
            BigDecimal profileConfidenceMultiplier,
            BigDecimal abilityGain,
            String oldRank,
            String proposedRank,
            List<String> judgeFlags,
            Map<String, Object> factorSnapshot,
            String scoringRuleVersion
    ) {
        public boolean requiresJudgeReview() {
            return judgeFlags != null && !judgeFlags.isEmpty();
        }
    }
}
