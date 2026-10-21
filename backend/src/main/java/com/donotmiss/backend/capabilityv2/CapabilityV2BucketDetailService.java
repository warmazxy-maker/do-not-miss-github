package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.abilityscore.AbilityAssessmentJobRepository;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentEntity;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentRepository;
import com.donotmiss.backend.abilityscore.JudgeDecision;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stage 15 read model: explains why one capability bucket has its current score.
 *
 * <p>Every score change of a V2 bucket is already persisted by the scoring pipeline as an
 * evidence link plus a score audit. This service only joins those rows with the original
 * growth record and replays them in scoring order. It never calls an LLM and never writes,
 * so milestones are derived facts that always agree with the audit trail.</p>
 */
@Service
@Transactional(readOnly = true)
public class CapabilityV2BucketDetailService {
    private static final String PROMOTION_GATE_FLAG = "RANK_PROMOTION_REQUIRES_JUDGE";
    private static final BigDecimal RECONCILE_TOLERANCE = new BigDecimal("0.0100");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final UserCapabilityStateRepository stateRepository;
    private final CapabilityBucketRepository bucketRepository;
    private final CapabilityEvidenceLinkRepository evidenceLinkRepository;
    private final CapabilityScoreAuditRepository scoreAuditRepository;
    private final AbilityEvidenceAssessmentRepository assessmentRepository;
    private final AbilityAssessmentJobRepository jobRepository;
    private final AchievementRecordRepository recordRepository;
    private final CapabilityPromotionReviewRepository reviewRepository;
    private final RankPromotionPolicy rankPolicy;
    private final ObjectMapper objectMapper;

    public CapabilityV2BucketDetailService(UserCapabilityStateRepository stateRepository,
                                           CapabilityBucketRepository bucketRepository,
                                           CapabilityEvidenceLinkRepository evidenceLinkRepository,
                                           CapabilityScoreAuditRepository scoreAuditRepository,
                                           AbilityEvidenceAssessmentRepository assessmentRepository,
                                           AbilityAssessmentJobRepository jobRepository,
                                           AchievementRecordRepository recordRepository,
                                           CapabilityPromotionReviewRepository reviewRepository,
                                           RankPromotionPolicy rankPolicy,
                                           ObjectMapper objectMapper) {
        this.stateRepository = stateRepository;
        this.bucketRepository = bucketRepository;
        this.evidenceLinkRepository = evidenceLinkRepository;
        this.scoreAuditRepository = scoreAuditRepository;
        this.assessmentRepository = assessmentRepository;
        this.jobRepository = jobRepository;
        this.recordRepository = recordRepository;
        this.reviewRepository = reviewRepository;
        this.rankPolicy = rankPolicy;
        this.objectMapper = objectMapper;
    }

    public BucketDetail detailForUser(String rawUserId, Long bucketId) {
        String userId = rawUserId == null ? "" : rawUserId.trim();
        if (userId.isEmpty() || bucketId == null) {
            throw ApiException.notFound("能力标签不存在");
        }
        // Ownership is proven by the user's own state row; a bucket id alone is not enough,
        // because function buckets are global and shared by every user.
        UserCapabilityStateEntity state = stateRepository.findByUserIdAndBucketId(userId, bucketId)
                .orElseThrow(() -> ApiException.notFound("能力标签不存在"));
        CapabilityBucketEntity bucket = bucketRepository.findById(bucketId)
                .filter(candidate -> candidate.getStatus() == CapabilityBucketStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("能力标签不存在"));

        List<CapabilityEvidenceLinkEntity> links = evidenceLinkRepository
                .findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(userId, bucketId).stream()
                .filter(link -> link.getStatus() != CapabilityEvidenceLinkStatus.SUPERSEDED)
                .toList();
        Map<Long, CapabilityScoreAuditEntity> auditByLinkId = latestAuditByLink(
                scoreAuditRepository.findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(userId, bucketId)
        );
        Map<Long, AchievementRecordEntity> recordByAssessmentId = recordsByAssessmentId(userId, links);

        List<TimelineItem> timeline = links.stream()
                .map(link -> toTimelineItem(
                        link,
                        auditByLinkId.get(link.getId()),
                        recordByAssessmentId.get(link.getLegacyAssessmentId())
                ))
                .toList();
        List<CapabilityPromotionReviewEntity> reviews = reviewRepository
                .findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(userId, bucketId);
        Replay replay = replay(timeline, reviews);

        BigDecimal explainedExperience = timeline.stream()
                .map(TimelineItem::score)
                .filter(Objects::nonNull)
                .map(ScoreChange::experienceGain)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stateExperience = zeroIfNull(state.getExperienceScore());

        return new BucketDetail(
                bucket.getId(),
                bucket.getBucketType(),
                bucket.getDisplayName(),
                bucket.getDescription(),
                bucket.getDomainName(),
                bucket.getGroupName(),
                stateExperience,
                zeroIfNull(state.getAbilityScore()),
                state.getAbilityUncertainty() == null ? BigDecimal.ONE : state.getAbilityUncertainty(),
                state.getRankName(),
                state.getEvidenceCount(),
                state.getUpdatedAt(),
                promotion(state, timeline, reviews, replay),
                new Consistency(
                        explainedExperience,
                        stateExperience,
                        explainedExperience.subtract(stateExperience).abs().compareTo(RECONCILE_TOLERANCE) <= 0,
                        replay.finalRank(),
                        replay.finalRank().equals(state.getRankName())
                ),
                timeline,
                replay.milestones()
        );
    }

    /**
     * Replays the audit trail with the shared {@link RankPromotionPolicy}. Scored evidence and
     * passed promotion reviews are the only two kinds of event that can change a bucket's rank,
     * so merging them in time order reproduces the stored rank exactly.
     */
    private Replay replay(List<TimelineItem> timeline, List<CapabilityPromotionReviewEntity> reviews) {
        List<Milestone> milestones = new ArrayList<>();
        Deque<CapabilityPromotionReviewEntity> passedReviews = reviews.stream()
                .filter(review -> review.getDecision() == JudgeDecision.PASS
                        && review.getAppliedRank() != null
                        && review.getCompletedAt() != null)
                .sorted(Comparator.comparing(CapabilityPromotionReviewEntity::getCompletedAt))
                .collect(Collectors.toCollection(ArrayDeque::new));
        ReplayCursor cursor = new ReplayCursor();
        TimelineItem largestGain = null;
        int scoredCount = 0;

        for (TimelineItem item : timeline) {
            applyReviewsUntil(passedReviews, item.scoredAt(), cursor, milestones);
            ScoreChange score = item.score();
            if (score == null) {
                continue;
            }
            scoredCount++;
            if (scoredCount == 1) {
                milestones.add(milestone(MilestoneType.FIRST_EVIDENCE, null, null, item));
            }

            String proposedRank = rankPolicy.rankFor(score.newAbilityScore());
            if (rankPolicy.requiresReview(cursor.appliedRank, proposedRank)) {
                // Only the evidence that opens a hold is a milestone; later evidence joins the same hold.
                if (cursor.holdTriggerLinkId == null) {
                    cursor.holdTriggerLinkId = item.evidenceLinkId();
                    milestones.add(milestone(MilestoneType.PROMOTION_PENDING_REVIEW,
                            cursor.appliedRank, proposedRank, item));
                }
            } else {
                String nextRank = rankPolicy.nextAppliedRank(cursor.appliedRank, proposedRank);
                if (rankPolicy.index(nextRank) > rankPolicy.index(cursor.appliedRank)) {
                    milestones.add(milestone(MilestoneType.RANK_UP, cursor.appliedRank, nextRank, item));
                }
                cursor.appliedRank = nextRank;
                cursor.holdTriggerLinkId = null;
            }

            if (score.experienceGain() != null && (largestGain == null
                    || score.experienceGain().compareTo(largestGain.score().experienceGain()) > 0)) {
                largestGain = item;
            }
        }
        applyReviewsUntil(passedReviews, null, cursor, milestones);

        // A single piece of evidence is trivially the largest; only call it out once there is a comparison.
        if (largestGain != null && scoredCount >= 2) {
            milestones.add(milestone(MilestoneType.LARGEST_GAIN, null, null, largestGain));
        }
        milestones.sort(Comparator.comparing(Milestone::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Replay(List.copyOf(milestones), cursor.appliedRank, cursor.holdTriggerLinkId);
    }

    private void applyReviewsUntil(Deque<CapabilityPromotionReviewEntity> passedReviews,
                                   Instant until,
                                   ReplayCursor cursor,
                                   List<Milestone> milestones) {
        while (!passedReviews.isEmpty()
                && (until == null || !passedReviews.peekFirst().getCompletedAt().isAfter(until))) {
            CapabilityPromotionReviewEntity review = passedReviews.pollFirst();
            if (rankPolicy.index(review.getAppliedRank()) > rankPolicy.index(cursor.appliedRank)) {
                milestones.add(new Milestone(
                        MilestoneType.REVIEW_PASSED,
                        cursor.appliedRank,
                        review.getAppliedRank(),
                        null,
                        review.getTriggerEvidenceLinkId(),
                        review.getCompletedAt()
                ));
                cursor.appliedRank = review.getAppliedRank();
            }
            cursor.holdTriggerLinkId = null;
        }
    }

    private Promotion promotion(UserCapabilityStateEntity state,
                                List<TimelineItem> timeline,
                                List<CapabilityPromotionReviewEntity> reviews,
                                Replay replay) {
        BigDecimal abilityScore = zeroIfNull(state.getAbilityScore());
        boolean pending = rankPolicy.promotionPending(state.getRankName(), abilityScore);
        CapabilityPromotionReviewEntity openReview = reviews.stream()
                .filter(review -> review.getStatus() == CapabilityPromotionReviewEntity.Status.IN_PROGRESS)
                .reduce((first, second) -> second)
                .orElse(null);
        CapabilityPromotionReviewEntity lastCompleted = reviews.stream()
                .filter(review -> review.getStatus() == CapabilityPromotionReviewEntity.Status.COMPLETED)
                .reduce((first, second) -> second)
                .orElse(null);
        Instant latestEvidenceAt = timeline.isEmpty() ? null : timeline.getLast().scoredAt();
        Instant retryAvailableAt = pending
                ? CapabilityPromotionReviews.retryAvailableAt(lastCompleted, latestEvidenceAt)
                : null;
        boolean canStart = pending && openReview == null
                && (retryAvailableAt == null || !Instant.now().isBefore(retryAvailableAt));

        return new Promotion(
                pending,
                state.getRankName(),
                rankPolicy.rankFor(abilityScore),
                pending ? replay.holdTriggerLinkId() : null,
                openReview == null ? null : CapabilityPromotionReviews.view(openReview, objectMapper),
                canStart,
                canStart ? null : retryAvailableAt,
                reviews.stream()
                        .filter(review -> review.getStatus() == CapabilityPromotionReviewEntity.Status.COMPLETED)
                        .map(review -> CapabilityPromotionReviews.view(review, objectMapper))
                        .toList()
                        .reversed()
        );
    }

    /** Milestones carry facts only; wording and rank labels belong to the client. */
    private Milestone milestone(MilestoneType type, String fromRank, String toRank, TimelineItem item) {
        return new Milestone(
                type,
                fromRank,
                toRank,
                item.score() == null ? null : item.score().experienceGain(),
                item.evidenceLinkId(),
                item.occurredAt()
        );
    }

    private TimelineItem toTimelineItem(CapabilityEvidenceLinkEntity link,
                                        CapabilityScoreAuditEntity audit,
                                        AchievementRecordEntity record) {
        Map<String, Object> factors = audit == null ? Map.of() : readMap(audit.getFactorSnapshotJson());
        return new TimelineItem(
                link.getId(),
                link.getSourceType(),
                link.getSourceId(),
                record == null ? null : record.getId(),
                record == null ? null : record.getEventTitle(),
                record == null ? null : record.getContent(),
                record == null ? null : record.getDid(),
                record == null ? null : record.getLearned(),
                link.getRoleName(),
                link.getRelevance(),
                link.getRelevanceConfidence(),
                link.getClaimedOutcome(),
                readList(link.getEvidenceFindingsJson()),
                link.getStatus(),
                advisoryJudgeFlags(factors),
                riskFlags(factors),
                audit == null ? null : new ScoreChange(
                        audit.getScoringRuleVersion(),
                        audit.getOldExperienceScore(),
                        audit.getExperienceGain(),
                        audit.getNewExperienceScore(),
                        audit.getOldAbilityScore(),
                        audit.getNewAbilityScore(),
                        audit.getOldAbilityUncertainty(),
                        audit.getNewAbilityUncertainty()
                ),
                audit == null ? null : new ScoreFactors(
                        stringValue(factors.get("sourceType")),
                        decimal(factors.get("sourceCredibilityPrior")),
                        decimal(factors.get("activityDifficulty")),
                        decimal(factors.get("completionQuality")),
                        decimal(factors.get("personalContribution")),
                        decimal(factors.get("originalRelevance")),
                        decimal(factors.get("adjustedRelevance")),
                        decimal(factors.get("dimensionBudgetMultiplier")),
                        decimal(factors.get("evidenceQuality")),
                        decimal(factors.get("assessmentConfidence")),
                        decimal(factors.get("novelty"))
                ),
                record == null || record.getCompletedAt() == null ? link.getCreatedAt() : record.getCompletedAt(),
                link.getCreatedAt(),
                link.getAgentRunId()
        );
    }

    private Map<Long, CapabilityScoreAuditEntity> latestAuditByLink(List<CapabilityScoreAuditEntity> audits) {
        // Ascending input order means a later rule version for the same link overwrites the earlier one.
        Map<Long, CapabilityScoreAuditEntity> byLink = new LinkedHashMap<>();
        audits.forEach(audit -> byLink.put(audit.getEvidenceLinkId(), audit));
        return byLink;
    }

    /**
     * Evidence links store the business source (event/challenge id), not the growth record.
     * The growth record is reached through the evaluator's assessment and job, loaded in three
     * batched queries instead of one query per timeline item.
     */
    private Map<Long, AchievementRecordEntity> recordsByAssessmentId(String userId,
                                                                     List<CapabilityEvidenceLinkEntity> links) {
        List<Long> assessmentIds = links.stream()
                .map(CapabilityEvidenceLinkEntity::getLegacyAssessmentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (assessmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> jobIdByAssessmentId = assessmentRepository.findAllById(assessmentIds).stream()
                .filter(assessment -> assessment.getJobId() != null)
                .collect(Collectors.toMap(AbilityEvidenceAssessmentEntity::getId,
                        AbilityEvidenceAssessmentEntity::getJobId));
        Map<Long, Long> recordIdByJobId = jobRepository.findAllById(jobIdByAssessmentId.values().stream()
                        .distinct()
                        .toList()).stream()
                .filter(job -> userId.equals(job.getUserId()) && job.getAchievementRecordId() != null)
                .collect(Collectors.toMap(AbilityAssessmentJobEntity::getId,
                        AbilityAssessmentJobEntity::getAchievementRecordId));
        Map<Long, AchievementRecordEntity> recordsById = recordRepository.findAllById(recordIdByJobId.values().stream()
                        .distinct()
                        .toList()).stream()
                .filter(record -> userId.equals(record.getUserId()))
                .collect(Collectors.toMap(AchievementRecordEntity::getId, Function.identity()));

        Map<Long, AchievementRecordEntity> result = new LinkedHashMap<>();
        jobIdByAssessmentId.forEach((assessmentId, jobId) -> {
            AchievementRecordEntity record = recordsById.get(recordIdByJobId.get(jobId));
            if (record != null) {
                result.put(assessmentId, record);
            }
        });
        return result;
    }

    private List<String> riskFlags(Map<String, Object> factors) {
        List<String> flags = new ArrayList<>(stringList(factors.get("assessmentRiskFlags")));
        stringList(factors.get("assignmentRiskFlags")).stream()
                .filter(flag -> !flags.contains(flag))
                .forEach(flags::add);
        return List.copyOf(flags);
    }

    /**
     * The promotion gate is reported once per bucket through {@link Promotion}; repeating it on
     * every evidence scored during a hold would read as if each one needed its own review.
     */
    private List<String> advisoryJudgeFlags(Map<String, Object> factors) {
        return stringList(factors.get("judgeFlags")).stream()
                .filter(flag -> !PROMOTION_GATE_FLAG.equals(flag))
                .toList();
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : List.copyOf(values.stream().filter(Objects::nonNull).toList());
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> values = objectMapper.readValue(json, OBJECT_MAP);
            return values == null ? Map.of() : values;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record Replay(List<Milestone> milestones, String finalRank, Long holdTriggerLinkId) {
    }

    private static final class ReplayCursor {
        private String appliedRank = "UNRATED";
        private Long holdTriggerLinkId;
    }

    public enum MilestoneType {
        FIRST_EVIDENCE,
        RANK_UP,
        PROMOTION_PENDING_REVIEW,
        REVIEW_PASSED,
        LARGEST_GAIN
    }

    /**
     * Whether this bucket is waiting for a promotion review, which evidence opened the hold,
     * and what the user can do about it right now.
     */
    public record Promotion(
            boolean pending,
            String currentRank,
            String targetRank,
            Long triggerEvidenceLinkId,
            CapabilityPromotionReviews.View openReview,
            boolean canStartReview,
            Instant retryAvailableAt,
            List<CapabilityPromotionReviews.View> history
    ) {
    }

    public record BucketDetail(
            Long bucketId,
            CapabilityBucketType bucketType,
            String displayName,
            String description,
            String domainName,
            String groupName,
            BigDecimal experienceScore,
            BigDecimal abilityScore,
            BigDecimal abilityUncertainty,
            String rankName,
            int evidenceCount,
            Instant updatedAt,
            Promotion promotion,
            Consistency consistency,
            List<TimelineItem> timeline,
            List<Milestone> milestones
    ) {
    }

    /** Self-check that the audit trail fully explains the current state. */
    public record Consistency(
            BigDecimal explainedExperience,
            BigDecimal stateExperience,
            boolean experienceReconciled,
            String replayedRank,
            boolean rankReconciled
    ) {
    }

    public record TimelineItem(
            Long evidenceLinkId,
            String sourceKind,
            String sourceId,
            Long achievementRecordId,
            String title,
            String summary,
            String did,
            String learned,
            CapabilityEvidenceRole role,
            BigDecimal relevance,
            BigDecimal relevanceConfidence,
            String claimedOutcome,
            List<String> evidenceFindings,
            CapabilityEvidenceLinkStatus status,
            List<String> judgeFlags,
            List<String> riskFlags,
            ScoreChange score,
            ScoreFactors factors,
            Instant occurredAt,
            Instant scoredAt,
            Long agentRunId
    ) {
    }

    public record ScoreChange(
            String scoringRuleVersion,
            BigDecimal oldExperienceScore,
            BigDecimal experienceGain,
            BigDecimal newExperienceScore,
            BigDecimal oldAbilityScore,
            BigDecimal newAbilityScore,
            BigDecimal oldAbilityUncertainty,
            BigDecimal newAbilityUncertainty
    ) {
    }

    public record ScoreFactors(
            String sourceType,
            BigDecimal sourceCredibilityPrior,
            BigDecimal activityDifficulty,
            BigDecimal completionQuality,
            BigDecimal personalContribution,
            BigDecimal originalRelevance,
            BigDecimal adjustedRelevance,
            BigDecimal dimensionBudgetMultiplier,
            BigDecimal evidenceQuality,
            BigDecimal assessmentConfidence,
            BigDecimal novelty
    ) {
    }

    public record Milestone(
            MilestoneType type,
            String fromRank,
            String toRank,
            BigDecimal experienceGain,
            Long evidenceLinkId,
            Instant occurredAt
    ) {
    }
}
