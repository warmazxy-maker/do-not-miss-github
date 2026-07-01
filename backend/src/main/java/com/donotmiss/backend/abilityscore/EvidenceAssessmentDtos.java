package com.donotmiss.backend.abilityscore;

import java.math.BigDecimal;
import java.util.List;

public final class EvidenceAssessmentDtos {
    private EvidenceAssessmentDtos() {
    }

    public record ModelResponse(
            String normalizedActivityType,
            BigDecimal activityDifficulty,
            BigDecimal activityDifficultyConfidence,
            BigDecimal completionQuality,
            BigDecimal completionQualityConfidence,
            BigDecimal personalContribution,
            BigDecimal personalContributionConfidence,
            BigDecimal assessmentConfidence,
            List<DimensionItem> dimensions,
            List<EvidenceFinding> evidenceFindings,
            NoveltyFeatures noveltyFeatures,
            List<String> riskFlags,
            JudgeRecommendation judgeRecommendation
    ) {
    }

    public record DimensionItem(
            String dimension,
            String normalizedDimension,
            BigDecimal relevance,
            BigDecimal confidence,
            String claimedOutcome,
            List<String> evidenceRefs
    ) {
    }

    public record EvidenceFinding(
            String evidenceId,
            String evidenceType,
            BigDecimal quality,
            String verificationStatus,
            List<String> supports
    ) {
    }

    public record NoveltyFeatures(
            String activityFamily,
            List<String> techniques,
            String difficultyBand
    ) {
    }

    public record JudgeRecommendation(
            Boolean suggestReview,
            List<String> reasons
    ) {
    }

    public record AssessmentResponse(
            Long jobId,
            Long assessmentId,
            String requestId,
            String userId,
            Long achievementRecordId,
            String status,
            String fairnessStatus,
            Long duplicateOfJobId,
            String mode,
            String evidenceHash,
            BigDecimal evidenceQuality,
            BigDecimal novelty,
            AbilityScoringExecution scoring
    ) {
    }
}
