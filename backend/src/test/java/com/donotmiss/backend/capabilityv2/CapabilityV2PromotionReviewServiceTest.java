package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import com.donotmiss.backend.abilityscore.AbilityScoringProfileEntity;
import com.donotmiss.backend.abilityscore.AbilityScoringProfileRepository;
import com.donotmiss.backend.abilityscore.JudgeDecision;
import com.donotmiss.backend.abilityscore.JudgeDecisionPolicy;
import com.donotmiss.backend.abilityscore.JudgeDtos;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityV2PromotionReviewServiceTest {
    private static final String USER = "student-1";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Mock
    private CapabilityV2BucketDetailService detailService;
    @Mock
    private CapabilityPromotionReviewRepository reviewRepository;
    @Mock
    private UserCapabilityStateRepository stateRepository;
    @Mock
    private AbilityScoringProfileRepository profileRepository;
    @Mock
    private OpenAiCompatibleLlmClient llmClient;
    @Mock
    private AgentRunService agentRunService;

    @Test
    void startCreatesOneOpenReviewWithExperienceFirstFallbackQuestions() {
        when(detailService.detailForUser(USER, 10L)).thenReturn(detail(promotion(true, true, null)));
        when(llmClient.chatForJson(anyString(), anyString(), eq(JudgeDtos.QuestionModelResponse.class)))
                .thenReturn(Optional.empty());
        when(stateRepository.findByUserIdAndBucketId(USER, 10L)).thenReturn(Optional.of(state("FOUNDATION", "55.0000")));
        when(profileRepository.findById(USER)).thenReturn(Optional.of(profile("0.5000")));
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CapabilityPromotionReviews.View view = service().start(USER, 10L);

        ArgumentCaptor<CapabilityPromotionReviewEntity> saved = ArgumentCaptor.forClass(CapabilityPromotionReviewEntity.class);
        verify(reviewRepository).save(saved.capture());
        assertEquals("student-1:10", saved.getValue().getOpenKey(), "unique open key blocks a second open review");
        assertEquals(102L, saved.getValue().getTriggerEvidenceLinkId());
        assertEquals(3, view.questions().size());
        assertTrue(view.questions().get(0).prompt().contains("Java 后端项目实战"),
                "the first question is anchored in the evidence that opened the hold");
    }

    @Test
    void startResumesTheOpenReviewWithoutCallingTheModel() {
        CapabilityPromotionReviews.View open = new CapabilityPromotionReviews.View(5L, "IN_PROGRESS", "PENDING",
                "FOUNDATION", "PROFICIENT", null, 102L, List.of(), List.of(), null, false, Instant.now(), null);
        when(detailService.detailForUser(USER, 10L)).thenReturn(detail(promotion(true, false, open)));

        assertEquals(5L, service().start(USER, 10L).id());
        verifyNoInteractions(llmClient, reviewRepository);
    }

    @Test
    void startRejectsBucketWithoutHeldPromotion() {
        when(detailService.detailForUser(USER, 10L)).thenReturn(detail(promotion(false, false, null)));

        assertThrows(ApiException.class, () -> service().start(USER, 10L));
        verifyNoInteractions(llmClient);
    }

    @Test
    void passUnlocksStraightToTheRankTheCurrentScoreEarns() {
        CapabilityPromotionReviewEntity review = openReview();
        UserCapabilityStateEntity state = state("FOUNDATION", "55.0000");
        stubSubmit(review);
        when(stateRepository.findByUserIdAndBucketId(USER, 10L)).thenReturn(Optional.of(state));
        AbilityScoringProfileEntity profile = profile("0.5000");
        when(profileRepository.findById(USER)).thenReturn(Optional.of(profile));
        stubGrades(9, 8, 8);

        CapabilityPromotionReviews.View view = service().submit(USER, 900L, answers());

        assertEquals("PASS", view.decision());
        assertEquals("PROFICIENT", state.getRankName(), "55 points earns PROFICIENT, skipping a separate DEVELOPING review");
        assertEquals("PROFICIENT", view.appliedRank());
        assertNull(review.getOpenKey(), "a completed review releases the open slot");
        assertEquals(0, new BigDecimal("55.0000").compareTo(state.getAbilityScore()), "a review never adds score");
        assertTrue(profile.getProfileConfidence().compareTo(new BigDecimal("0.5000")) > 0);
    }

    @Test
    void failKeepsTheRankAndLowersProfileConfidence() {
        CapabilityPromotionReviewEntity review = openReview();
        stubSubmit(review);
        AbilityScoringProfileEntity profile = profile("0.5000");
        when(profileRepository.findById(USER)).thenReturn(Optional.of(profile));
        stubGrades(2, 2, 2);

        CapabilityPromotionReviews.View view = service().submit(USER, 900L, answers());

        assertEquals("FAIL", view.decision());
        assertNull(view.appliedRank());
        verify(stateRepository, never()).save(any());
        assertTrue(profile.getProfileConfidence().compareTo(new BigDecimal("0.5000")) < 0);
    }

    @Test
    void unavailableModelChangesNothingAndMarksTheReviewUngraded() {
        CapabilityPromotionReviewEntity review = openReview();
        stubSubmit(review);
        when(llmClient.chatForJson(anyString(), anyString(), eq(JudgeDtos.EvaluationModelResponse.class)))
                .thenReturn(Optional.empty());

        CapabilityPromotionReviews.View view = service().submit(USER, 900L, answers());

        assertEquals("MANUAL_REVIEW", view.decision());
        assertFalse(view.modelEvaluated(), "an ungraded review must not trigger the retry cooldown");
        verifyNoInteractions(stateRepository, profileRepository);
    }

    @Test
    void submitHidesOtherUsersReviews() {
        CapabilityPromotionReviewEntity review = openReview();
        review.setUserId("someone-else");
        when(reviewRepository.findById(900L)).thenReturn(Optional.of(review));

        assertThrows(ApiException.class, () -> service().submit(USER, 900L, answers()));
        verifyNoInteractions(llmClient);
    }

    private CapabilityV2PromotionReviewService service() {
        return new CapabilityV2PromotionReviewService(
                detailService, reviewRepository, stateRepository, profileRepository,
                new RankPromotionPolicy(new AbilityScoreCalculator()), new JudgeDecisionPolicy(),
                llmClient, agentRunService, MAPPER
        );
    }

    private void stubSubmit(CapabilityPromotionReviewEntity review) {
        when(reviewRepository.findById(900L)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(detailService.detailForUser(USER, 10L)).thenReturn(detail(promotion(true, false, null)));
    }

    private void stubGrades(int q1, int q2, int q3) {
        when(llmClient.chatForJson(anyString(), anyString(), eq(JudgeDtos.EvaluationModelResponse.class)))
                .thenReturn(Optional.of(new JudgeDtos.EvaluationModelResponse(List.of(
                        new JudgeDtos.RubricItem("q1", q1, 10, "ok", List.of()),
                        new JudgeDtos.RubricItem("q2", q2, 10, "ok", List.of()),
                        new JudgeDtos.RubricItem("q3", q3, 10, "ok", List.of())
                ), "overall")));
    }

    private List<JudgeDtos.Answer> answers() {
        return List.of(
                new JudgeDtos.Answer("q1", "我负责订单接口的拆分和幂等设计"),
                new JudgeDtos.Answer("q2", "重复消费时用唯一约束兜底并记录日志"),
                new JudgeDtos.Answer("q3", "并发再高时数据库连接池会先成为瓶颈")
        );
    }

    private CapabilityPromotionReviewEntity openReview() {
        CapabilityPromotionReviewEntity review = new CapabilityPromotionReviewEntity();
        ReflectionTestUtils.setField(review, "id", 900L);
        review.setUserId(USER);
        review.setBucketId(10L);
        review.setFromRank("FOUNDATION");
        review.setTargetRank("DEVELOPING");
        review.setAbilityScoreAtStart(new BigDecimal("33.0000"));
        review.setOpenKey(CapabilityPromotionReviewEntity.openKeyFor(USER, 10L));
        review.setQuestionsJson("[{\"id\":\"q1\",\"prompt\":\"a\",\"focus\":\"f\",\"maxScore\":10},"
                + "{\"id\":\"q2\",\"prompt\":\"b\",\"focus\":\"f\",\"maxScore\":10},"
                + "{\"id\":\"q3\",\"prompt\":\"c\",\"focus\":\"f\",\"maxScore\":10}]");
        return review;
    }

    private CapabilityV2BucketDetailService.Promotion promotion(boolean pending, boolean canStart,
                                                               CapabilityPromotionReviews.View open) {
        return new CapabilityV2BucketDetailService.Promotion(
                pending, "FOUNDATION", "PROFICIENT", pending ? 102L : null, open, canStart, null, List.of());
    }

    private CapabilityV2BucketDetailService.BucketDetail detail(CapabilityV2BucketDetailService.Promotion promotion) {
        CapabilityV2BucketDetailService.TimelineItem trigger = new CapabilityV2BucketDetailService.TimelineItem(
                102L, "ACTIVITY_COMPLETION", "88", 701L, "Java 后端项目实战", "内容", "做了接口", "学到了幂等",
                CapabilityEvidenceRole.NONE, new BigDecimal("0.9"), new BigDecimal("0.8"), "完成订单模块",
                List.of("集合与泛型"), CapabilityEvidenceLinkStatus.REVIEW_REQUIRED, List.of(), List.of(),
                null, null, Instant.parse("2026-08-02T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"), null);
        return new CapabilityV2BucketDetailService.BucketDetail(
                10L, CapabilityBucketType.KNOWLEDGE, "Java", "Java 语言", null, null,
                new BigDecimal("40"), new BigDecimal("55"), new BigDecimal("0.3"), "FOUNDATION", 3, Instant.now(),
                promotion,
                new CapabilityV2BucketDetailService.Consistency(new BigDecimal("40"), new BigDecimal("40"), true,
                        "FOUNDATION", true),
                List.of(trigger),
                List.of()
        );
    }

    private UserCapabilityStateEntity state(String rank, String ability) {
        UserCapabilityStateEntity state = new UserCapabilityStateEntity();
        ReflectionTestUtils.setField(state, "id", 1L);
        state.setUserId(USER);
        state.setBucketId(10L);
        state.setRankName(rank);
        state.setAbilityScore(new BigDecimal(ability));
        return state;
    }

    private AbilityScoringProfileEntity profile(String confidence) {
        AbilityScoringProfileEntity profile = new AbilityScoringProfileEntity();
        profile.setUserId(USER);
        profile.setProfileConfidence(new BigDecimal(confidence));
        return profile;
    }
}
