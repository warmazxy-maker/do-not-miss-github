package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.abilityscore.AbilityAssessmentJobRepository;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentEntity;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentRepository;
import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import com.donotmiss.backend.abilityscore.JudgeDecision;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.achievement.AchievementSourceType;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityV2BucketDetailServiceTest {
    private static final String USER = "student-1";

    @Mock
    private UserCapabilityStateRepository stateRepository;
    @Mock
    private CapabilityBucketRepository bucketRepository;
    @Mock
    private CapabilityEvidenceLinkRepository evidenceLinkRepository;
    @Mock
    private CapabilityScoreAuditRepository scoreAuditRepository;
    @Mock
    private AbilityEvidenceAssessmentRepository assessmentRepository;
    @Mock
    private AbilityAssessmentJobRepository jobRepository;
    @Mock
    private AchievementRecordRepository recordRepository;
    @Mock
    private CapabilityPromotionReviewRepository reviewRepository;

    @Test
    void replaysEvidenceAndPassedReviewIntoMilestonesAndReconciliation() {
        stubHeldBucket("DEVELOPING");
        when(reviewRepository.findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(USER, 10L)).thenReturn(List.of(
                review(JudgeDecision.PASS, true, "2026-08-05T00:00:00Z", "DEVELOPING")
        ));

        CapabilityV2BucketDetailService.BucketDetail detail = service().detailForUser(USER, 10L);

        assertEquals(3, detail.timeline().size(), "superseded evidence is hidden");
        assertEquals("Java 后端项目实战", detail.timeline().get(0).title());
        assertEquals(List.of("集合与泛型"), detail.timeline().get(0).evidenceFindings());
        assertNull(detail.timeline().get(1).title(), "a job owned by another user must not leak its record");
        assertEquals(List.of("HIGH_SINGLE_ACTIVITY_GAIN"), detail.timeline().get(1).judgeFlags(),
                "the promotion gate is reported once per bucket, not on every evidence");
        assertEquals(new BigDecimal("0.8"), detail.timeline().get(0).factors().activityDifficulty().stripTrailingZeros());

        assertEquals(List.of(
                CapabilityV2BucketDetailService.MilestoneType.FIRST_EVIDENCE,
                CapabilityV2BucketDetailService.MilestoneType.RANK_UP,
                CapabilityV2BucketDetailService.MilestoneType.PROMOTION_PENDING_REVIEW,
                CapabilityV2BucketDetailService.MilestoneType.LARGEST_GAIN,
                CapabilityV2BucketDetailService.MilestoneType.REVIEW_PASSED
        ), types(detail));
        assertEquals(102L, detail.milestones().get(2).evidenceLinkId(), "the hold opens on evidence 102 only");
        assertEquals("FOUNDATION", detail.milestones().get(4).fromRank());
        assertEquals("DEVELOPING", detail.milestones().get(4).toRank(), "rank changes only when the review passes");

        assertEquals(0, new BigDecimal("15").compareTo(detail.consistency().explainedExperience()));
        assertTrue(detail.consistency().experienceReconciled());
        assertEquals("DEVELOPING", detail.consistency().replayedRank());
        assertTrue(detail.consistency().rankReconciled());
        assertFalse(detail.promotion().pending());
        assertEquals(1, detail.promotion().history().size());
    }

    @Test
    void heldBucketPointsAtTheEvidenceThatOpenedTheHoldAndAllowsReview() {
        stubHeldBucket("FOUNDATION");
        when(reviewRepository.findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(USER, 10L)).thenReturn(List.of());

        CapabilityV2BucketDetailService.BucketDetail detail = service().detailForUser(USER, 10L);
        CapabilityV2BucketDetailService.Promotion promotion = detail.promotion();

        assertTrue(promotion.pending());
        assertEquals("FOUNDATION", promotion.currentRank());
        assertEquals("DEVELOPING", promotion.targetRank());
        assertEquals(102L, promotion.triggerEvidenceLinkId(), "evidence 103 joins the hold instead of opening another");
        assertTrue(promotion.canStartReview());
        assertEquals("FOUNDATION", detail.consistency().replayedRank());
        assertTrue(detail.consistency().rankReconciled());
        assertEquals(1, types(detail).stream()
                .filter(type -> type == CapabilityV2BucketDetailService.MilestoneType.PROMOTION_PENDING_REVIEW)
                .count());
    }

    @Test
    void failedReviewWaitsForNewEvidenceOrCooldown() {
        stubHeldBucket("FOUNDATION");
        Instant failedAt = Instant.now().minusSeconds(3600);
        when(reviewRepository.findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(USER, 10L)).thenReturn(List.of(
                review(JudgeDecision.FAIL, true, failedAt.toString(), null)
        ));

        CapabilityV2BucketDetailService.Promotion promotion = service().detailForUser(USER, 10L).promotion();

        assertTrue(promotion.pending());
        assertFalse(promotion.canStartReview(), "the latest evidence is older than the failed review");
        assertEquals(failedAt.plus(CapabilityPromotionReviews.RETRY_COOLDOWN), promotion.retryAvailableAt());
    }

    @Test
    void reviewTheModelCouldNotGradeCanBeRetriedImmediately() {
        stubHeldBucket("FOUNDATION");
        when(reviewRepository.findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(USER, 10L)).thenReturn(List.of(
                review(JudgeDecision.MANUAL_REVIEW, false, Instant.now().minusSeconds(60).toString(), null)
        ));

        assertTrue(service().detailForUser(USER, 10L).promotion().canStartReview());
    }

    /**
     * Java bucket: 0 -> 12 (FOUNDATION), 12 -> 33 (earns DEVELOPING, hold opens), 33 -> 36 (still held),
     * plus one superseded link that must stay hidden.
     */
    private void stubHeldBucket(String stateRank) {
        when(stateRepository.findByUserIdAndBucketId(USER, 10L))
                .thenReturn(Optional.of(state("15.0000", "36.0000", stateRank, 3)));
        when(bucketRepository.findById(10L)).thenReturn(Optional.of(bucket(CapabilityBucketStatus.ACTIVE)));
        when(evidenceLinkRepository.findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(USER, 10L)).thenReturn(List.of(
                link(101L, 501L, CapabilityEvidenceLinkStatus.SCORED, "2026-08-01T00:00:00Z"),
                link(102L, 502L, CapabilityEvidenceLinkStatus.REVIEW_REQUIRED, "2026-08-02T00:00:00Z"),
                link(103L, null, CapabilityEvidenceLinkStatus.SCORED, "2026-08-03T00:00:00Z"),
                link(104L, null, CapabilityEvidenceLinkStatus.SUPERSEDED, "2026-08-04T00:00:00Z")
        ));
        when(scoreAuditRepository.findByUserIdAndBucketIdOrderByCreatedAtAscIdAsc(USER, 10L)).thenReturn(List.of(
                audit(101L, "0", "5.0000", "0", "12.0000", "[]"),
                audit(102L, "5.0000", "8.0000", "12.0000", "33.0000",
                        "[\"HIGH_SINGLE_ACTIVITY_GAIN\",\"RANK_PROMOTION_REQUIRES_JUDGE\"]"),
                audit(103L, "13.0000", "2.0000", "33.0000", "36.0000", "[\"RANK_PROMOTION_REQUIRES_JUDGE\"]")
        ));
        when(assessmentRepository.findAllById(any())).thenReturn(List.of(assessment(501L, 601L), assessment(502L, 602L)));
        when(jobRepository.findAllById(any())).thenReturn(List.of(job(601L, USER, 701L), job(602L, "someone-else", 702L)));
        when(recordRepository.findAllById(any())).thenReturn(List.of(record(701L, "Java 后端项目实战")));
    }

    private List<CapabilityV2BucketDetailService.MilestoneType> types(CapabilityV2BucketDetailService.BucketDetail detail) {
        return detail.milestones().stream().map(CapabilityV2BucketDetailService.Milestone::type).toList();
    }

    private CapabilityPromotionReviewEntity review(JudgeDecision decision, boolean modelEvaluated,
                                                   String completedAt, String appliedRank) {
        CapabilityPromotionReviewEntity review = new CapabilityPromotionReviewEntity();
        ReflectionTestUtils.setField(review, "id", 900L);
        ReflectionTestUtils.setField(review, "createdAt", Instant.parse(completedAt).minusSeconds(600));
        review.setUserId(USER);
        review.setBucketId(10L);
        review.setTriggerEvidenceLinkId(102L);
        review.setFromRank("FOUNDATION");
        review.setTargetRank("DEVELOPING");
        review.setStatus(CapabilityPromotionReviewEntity.Status.COMPLETED);
        review.setDecision(decision);
        review.setModelEvaluated(modelEvaluated);
        review.setAppliedRank(appliedRank);
        review.setCompletedAt(Instant.parse(completedAt));
        return review;
    }

    @Test
    void rejectsBucketWithoutOwnStateBeforeReadingEvidence() {
        when(stateRepository.findByUserIdAndBucketId(USER, 99L)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service().detailForUser(USER, 99L));
        verifyNoInteractions(evidenceLinkRepository, scoreAuditRepository);
    }

    @Test
    void rejectsDisabledBucket() {
        when(stateRepository.findByUserIdAndBucketId(USER, 10L))
                .thenReturn(Optional.of(state("1.0000", "1.0000", "UNRATED", 1)));
        when(bucketRepository.findById(10L)).thenReturn(Optional.of(bucket(CapabilityBucketStatus.DISABLED)));

        assertThrows(ApiException.class, () -> service().detailForUser(USER, 10L));
    }

    private CapabilityV2BucketDetailService service() {
        return new CapabilityV2BucketDetailService(
                stateRepository, bucketRepository, evidenceLinkRepository, scoreAuditRepository,
                assessmentRepository, jobRepository, recordRepository, reviewRepository,
                new RankPromotionPolicy(new AbilityScoreCalculator()), new ObjectMapper()
        );
    }

    private UserCapabilityStateEntity state(String experience, String ability, String rank, int evidenceCount) {
        UserCapabilityStateEntity state = new UserCapabilityStateEntity();
        ReflectionTestUtils.setField(state, "id", 1L);
        state.setUserId(USER);
        state.setBucketId(10L);
        state.setExperienceScore(new BigDecimal(experience));
        state.setAbilityScore(new BigDecimal(ability));
        state.setAbilityUncertainty(new BigDecimal("0.6000"));
        state.setRankName(rank);
        state.setEvidenceCount(evidenceCount);
        return state;
    }

    private CapabilityBucketEntity bucket(CapabilityBucketStatus status) {
        CapabilityBucketEntity bucket = new CapabilityBucketEntity();
        ReflectionTestUtils.setField(bucket, "id", 10L);
        bucket.setBucketType(CapabilityBucketType.KNOWLEDGE);
        bucket.setOwnerUserId(USER);
        bucket.setNormalizedName("java");
        bucket.setDisplayName("Java");
        bucket.setStatus(status);
        return bucket;
    }

    private CapabilityEvidenceLinkEntity link(Long id, Long assessmentId, CapabilityEvidenceLinkStatus status,
                                              String createdAt) {
        CapabilityEvidenceLinkEntity link = new CapabilityEvidenceLinkEntity();
        ReflectionTestUtils.setField(link, "id", id);
        ReflectionTestUtils.setField(link, "createdAt", Instant.parse(createdAt));
        link.setUserId(USER);
        link.setBucketId(10L);
        link.setSourceType(CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION.name());
        link.setSourceId("88");
        link.setLegacyAssessmentId(assessmentId);
        link.setRoleName(CapabilityEvidenceRole.NONE);
        link.setRelevance(new BigDecimal("0.9000"));
        link.setRelevanceConfidence(new BigDecimal("0.8000"));
        link.setEvidenceFindingsJson("[\"集合与泛型\"]");
        link.setStatus(status);
        return link;
    }

    private CapabilityScoreAuditEntity audit(Long linkId, String oldExperience, String gain,
                                             String oldAbility, String newAbility, String judgeFlags) {
        CapabilityScoreAuditEntity audit = new CapabilityScoreAuditEntity();
        audit.setUserId(USER);
        audit.setBucketId(10L);
        audit.setEvidenceLinkId(linkId);
        audit.setScoringRuleVersion("ability-score-v2.0");
        audit.setOldExperienceScore(new BigDecimal(oldExperience));
        audit.setExperienceGain(new BigDecimal(gain));
        audit.setNewExperienceScore(new BigDecimal(oldExperience).add(new BigDecimal(gain)));
        audit.setOldAbilityScore(new BigDecimal(oldAbility));
        audit.setNewAbilityScore(new BigDecimal(newAbility));
        audit.setFactorSnapshotJson("{\"activityDifficulty\":0.8000,\"sourceType\":\"ORGANIZATION_ACTIVITY_RECORD\","
                + "\"judgeFlags\":" + judgeFlags + ",\"assignmentRiskFlags\":[]}");
        return audit;
    }

    private AbilityEvidenceAssessmentEntity assessment(Long id, Long jobId) {
        AbilityEvidenceAssessmentEntity assessment = new AbilityEvidenceAssessmentEntity();
        ReflectionTestUtils.setField(assessment, "id", id);
        assessment.setJobId(jobId);
        return assessment;
    }

    private AbilityAssessmentJobEntity job(Long id, String userId, Long recordId) {
        AbilityAssessmentJobEntity job = new AbilityAssessmentJobEntity();
        ReflectionTestUtils.setField(job, "id", id);
        job.setUserId(userId);
        job.setAchievementRecordId(recordId);
        return job;
    }

    private AchievementRecordEntity record(Long id, String title) {
        AchievementRecordEntity record = new AchievementRecordEntity();
        ReflectionTestUtils.setField(record, "id", id);
        record.setUserId(USER);
        record.setSourceType(AchievementSourceType.EVENT);
        record.setEventTitle(title);
        record.setCompletedAt(Instant.parse("2026-07-31T10:00:00Z"));
        return record;
    }
}
