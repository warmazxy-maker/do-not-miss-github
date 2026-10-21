package com.donotmiss.backend.capabilityv2;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stable contracts shared by the future classifier, score adapter and frontend read model.
 * No Controller is introduced in Stage 05: these records intentionally do not change user
 * facing behavior until Stage 11 switches the read path.
 */
public final class CapabilityV2Dtos {
    private CapabilityV2Dtos() {
    }

    public record EvidenceSource(
            String sourceType,
            String sourceId,
            String userId,
            String evidenceHash,
            String title,
            String description,
            String reflection,
            String qualityReportJson
    ) {
    }

    public record BucketCandidate(
            Long bucketId,
            CapabilityBucketType bucketType,
            String displayName,
            String domainName,
            String groupName,
            String description,
            List<String> aliases
    ) {
    }

    public record BucketAssignment(
            Long bucketId,
            CapabilityBucketType bucketType,
            String bucketName,
            BigDecimal relevance,
            BigDecimal relevanceConfidence,
            CapabilityEvidenceRole role,
            String claimedOutcome,
            List<String> evidenceFindings,
            List<String> riskFlags
    ) {
    }

    public record ClassificationRequest(
            String requestId,
            EvidenceSource evidence,
            List<BucketCandidate> knowledgeCandidates,
            List<BucketCandidate> functionCandidates,
            String promptVersion,
            String modelName
    ) {
    }

    public record ClassificationResult(
            List<BucketAssignment> knowledgeAssignments,
            List<BucketAssignment> functionAssignments,
            String rawReasoningSummary
    ) {
    }

    public record ScoreCommand(
            String userId,
            Long bucketId,
            Long evidenceLinkId,
            BigDecimal activityDifficulty,
            BigDecimal completionQuality,
            BigDecimal personalContribution,
            BigDecimal assessmentConfidence,
            BigDecimal relevance,
            BigDecimal relevanceConfidence,
            String scoringRuleVersion
    ) {
    }
}
