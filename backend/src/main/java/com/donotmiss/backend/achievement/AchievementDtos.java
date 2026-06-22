package com.donotmiss.backend.achievement;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class AchievementDtos {
    public record ReflectionRequest(
            @Size(max = 2000) String did,
            @Size(max = 2000) String learned
    ) {
    }

    public record AchievementRecordResponse(
            Long id,
            String sourceType,
            Long sourceId,
            Long eventId,
            String eventTitle,
            String organizationName,
            String category,
            LocalDateTime eventStartTime,
            String location,
            String content,
            String benefitType,
            String skill,
            BigDecimal moneyAmount,
            Instant completedAt,
            String did,
            String learned
    ) {
        public static AchievementRecordResponse from(AchievementRecordEntity record) {
            return new AchievementRecordResponse(
                    record.getId(),
                    record.getSourceType().name(),
                    record.getSourceId(),
                    record.getEventId(),
                    record.getEventTitle(),
                    record.getOrganizationName(),
                    record.getCategory(),
                    record.getEventStartTime(),
                    record.getLocation(),
                    record.getContent(),
                    record.getBenefitType().label(),
                    record.getSkill(),
                    record.getMoneyAmount(),
                    record.getCompletedAt(),
                    record.getDid(),
                    record.getLearned()
            );
        }
    }

    public record CategoryCount(String category, long count) {
    }

    public record GrowthPoint(int order, Instant completedAt, Map<String, Integer> scores) {
    }

    public record AchievementSummary(
            long completedCount,
            long categoryCount,
            List<CategoryCount> categoryCounts,
            List<GrowthPoint> growthCurve
    ) {
    }

    public record GrowthTagResponse(
            Long id,
            String name,
            String normalizedName,
            String description,
            int score,
            int evidenceCount,
            int importanceScore,
            Instant lastUpdatedAt
    ) {
        public static GrowthTagResponse from(GrowthTagEntity tag) {
            return new GrowthTagResponse(
                    tag.getId(),
                    tag.getName(),
                    tag.getNormalizedName(),
                    tag.getDescription(),
                    tag.getScore(),
                    tag.getEvidenceCount(),
                    tag.getImportanceScore(),
                    tag.getLastUpdatedAt()
            );
        }
    }

    public record GrowthTagEvidenceResponse(
            Long id,
            Long tagId,
            Long recordId,
            String sourceType,
            Long sourceId,
            String title,
            String summary,
            String did,
            String learned,
            int scoreDelta,
            boolean milestone,
            String milestoneReason,
            Instant occurredAt
    ) {
        public static GrowthTagEvidenceResponse from(GrowthTagEvidenceEntity evidence) {
            return new GrowthTagEvidenceResponse(
                    evidence.getId(),
                    evidence.getTag().getId(),
                    evidence.getRecord().getId(),
                    evidence.getSourceType().name(),
                    evidence.getSourceId(),
                    evidence.getTitle(),
                    evidence.getSummary(),
                    evidence.getDid(),
                    evidence.getLearned(),
                    evidence.getScoreDelta(),
                    evidence.isMilestone(),
                    evidence.getMilestoneReason(),
                    evidence.getOccurredAt()
            );
        }
    }

    public record GrowthTagDetailResponse(
            GrowthTagResponse tag,
            List<GrowthTagEvidenceResponse> evidences
    ) {
    }

    public record MilestoneRequest(
            boolean milestone,
            @Size(max = 500) String reason
    ) {
    }

    public record GrowthTagRebuildResponse(
            int recordCount,
            int tagCount,
            int evidenceCount
    ) {
    }

    public record GrowthTagModelResponse(
            List<GrowthTagModelItem> tags
    ) {
    }

    public record GrowthTagModelItem(
            String name,
            String normalizedName,
            String description,
            Integer scoreDelta,
            String evidenceSummary,
            Boolean milestone,
            String milestoneReason
    ) {
    }
}
