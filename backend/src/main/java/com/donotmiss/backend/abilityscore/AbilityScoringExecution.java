package com.donotmiss.backend.abilityscore;

import java.math.BigDecimal;
import java.util.List;

public record AbilityScoringExecution(
        Long assessmentId,
        String requestId,
        int scoredDimensions,
        int skippedDimensions,
        List<DimensionResult> results
) {
    public record DimensionResult(
            Long scoreResultId,
            Long abilityStateId,
            String dimension,
            BigDecimal verifiedExperienceGain,
            BigDecimal newAbilityScore,
            BigDecimal newAbilityUncertainty,
            String effectiveRank,
            String proposedRank,
            AbilityScoreResultStatus status,
            List<String> judgeFlags,
            boolean reusedExistingResult,
            boolean correctionPending
    ) {
    }
}
