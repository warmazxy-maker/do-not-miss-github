package com.donotmiss.backend.recommendation;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.event.EventCategory;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import com.donotmiss.backend.memory.UserProfileSnapshotDirtyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationFeedbackServiceTest {
    @Mock
    private RecommendationImpressionRepository impressionRepository;
    @Mock
    private RecommendationFeedbackRepository feedbackRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private UserProfileSnapshotDirtyService profileDirtyService;
    @Mock
    private EvalCandidateRepository evalCandidateRepository;

    // ---- suppression: the latest explicit verdict per event decides ----

    @Test
    void latestExplicitVerdictDecidesSuppression() {
        List<RecommendationFeedbackEntity> oldestFirst = List.of(
                feedback(1L, 10L, RecommendationFeedbackType.NOT_RELEVANT),
                feedback(2L, 20L, RecommendationFeedbackType.INTERESTED),
                feedback(3L, 10L, RecommendationFeedbackType.INTERESTED),   // changed mind about 10
                feedback(4L, 20L, RecommendationFeedbackType.NOT_RELEVANT), // changed mind about 20
                feedback(5L, 30L, RecommendationFeedbackType.NOT_RELEVANT));

        assertEquals(Set.of(20L, 30L), RecommendationFeedbackService.suppressed(oldestFirst));
        assertEquals(Set.of(), RecommendationFeedbackService.suppressed(List.of()));
    }

    // ---- impressions ----

    @Test
    @SuppressWarnings("unchecked")
    void impressionsKeepRankScoreAndRun() {
        service().recordImpressions(77L, "demo-student", "Spring Boot 线下活动",
                List.of(recommended(58L, 91), recommended(31L, 70)));

        ArgumentCaptor<List<RecommendationImpressionEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(impressionRepository).saveAll(saved.capture());
        List<RecommendationImpressionEntity> rows = saved.getValue();
        assertEquals(2, rows.size());
        assertEquals(58L, rows.get(0).getEventId());
        assertEquals(1, rows.get(0).getRankNo());
        assertEquals(2, rows.get(1).getRankNo());
        assertEquals(70, rows.get(1).getScore());
        assertEquals(77L, rows.get(0).getRunId());
    }

    // ---- explicit feedback ----

    @Test
    void notRelevantReplacesPreviousVerdictAndKeepsReason() {
        RecommendationImpressionEntity impression = impression(5L, 58L);
        when(impressionRepository.findFirstByRunIdAndUserIdAndEventId(77L, "demo-student", 58L)).thenReturn(Optional.of(impression));
        List<RecommendationFeedbackEntity> previous = List.of(feedback(9L, 58L, RecommendationFeedbackType.INTERESTED));
        when(feedbackRepository.findByImpressionIdAndExplicitFeedbackTrue(5L)).thenReturn(previous);

        RecommendationFeedbackDtos.FeedbackState state = service().submit("demo-student",
                new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, RecommendationFeedbackType.NOT_RELEVANT, "  太远了 "));

        verify(feedbackRepository).deleteAll(previous);
        ArgumentCaptor<RecommendationFeedbackEntity> saved = ArgumentCaptor.forClass(RecommendationFeedbackEntity.class);
        verify(feedbackRepository).save(saved.capture());
        assertEquals(RecommendationFeedbackType.NOT_RELEVANT, saved.getValue().getFeedbackType());
        assertTrue(saved.getValue().isExplicitFeedback());
        assertEquals("太远了", saved.getValue().getReason());
        assertEquals("太远了", state.reason());
    }

    @Test
    void clearingRemovesTheVerdictWithoutSavingANewOne() {
        when(impressionRepository.findFirstByRunIdAndUserIdAndEventId(77L, "demo-student", 58L))
                .thenReturn(Optional.of(impression(5L, 58L)));
        when(feedbackRepository.findByImpressionIdAndExplicitFeedbackTrue(5L)).thenReturn(List.of());

        RecommendationFeedbackDtos.FeedbackState state = service().submit("demo-student",
                new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, null, null));

        assertNull(state.type());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void studentCanOnlyRateWhatTheyWereShown() {
        when(impressionRepository.findFirstByRunIdAndUserIdAndEventId(77L, "someone-else", 58L)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> service().submit("someone-else",
                new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, RecommendationFeedbackType.INTERESTED, null)));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void implicitTypesCannotBeSubmittedByHand() {
        ApiException error = assertThrows(ApiException.class, () -> service().submit("demo-student",
                new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, RecommendationFeedbackType.COMPLETED, null)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    // ---- implicit feedback ----

    @Test
    void reservationIsAttributedToTheLatestImpressionInTheWindow() {
        when(impressionRepository.findFirstByUserIdAndEventIdAndShownAtGreaterThanEqualOrderByShownAtDesc(
                eq("demo-student"), eq(58L), any(Instant.class))).thenReturn(Optional.of(impression(5L, 58L)));
        when(feedbackRepository.existsByImpressionIdAndFeedbackType(5L, RecommendationFeedbackType.RESERVED)).thenReturn(false);
        when(feedbackRepository.save(any())).thenAnswer(invocation -> {
            RecommendationFeedbackEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 100L);
            return entity;
        });

        Optional<Long> id = service().recordImplicit(
                RecommendationActivityEvent.forEvent("demo-student", 58L, RecommendationFeedbackType.RESERVED));

        assertEquals(Optional.of(100L), id);
    }

    @Test
    void actionWithoutARecentRecommendationIsNotFeedback() {
        when(impressionRepository.findFirstByUserIdAndEventIdAndShownAtGreaterThanEqualOrderByShownAtDesc(
                eq("demo-student"), eq(58L), any(Instant.class))).thenReturn(Optional.empty());

        assertTrue(service().recordImplicit(
                RecommendationActivityEvent.forEvent("demo-student", 58L, RecommendationFeedbackType.RESERVED)).isEmpty());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void repeatedActionIsRecordedOnce() {
        when(impressionRepository.findFirstByUserIdAndEventIdAndShownAtGreaterThanEqualOrderByShownAtDesc(
                eq("demo-student"), eq(58L), any(Instant.class))).thenReturn(Optional.of(impression(5L, 58L)));
        when(feedbackRepository.existsByImpressionIdAndFeedbackType(5L, RecommendationFeedbackType.COMPLETED)).thenReturn(true);

        assertTrue(service().recordImplicit(
                RecommendationActivityEvent.forEvent("demo-student", 58L, RecommendationFeedbackType.COMPLETED)).isEmpty());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void followIsAttributedThroughTheOrganization() {
        when(impressionRepository.findRecentForOrganization(eq("demo-student"), eq("Kansai Career Tech"), any(Instant.class), any()))
                .thenReturn(List.of(impression(5L, 58L)));
        when(feedbackRepository.existsByImpressionIdAndFeedbackType(5L, RecommendationFeedbackType.FOLLOWED)).thenReturn(false);
        when(feedbackRepository.save(any())).thenAnswer(invocation -> {
            RecommendationFeedbackEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 101L);
            return entity;
        });

        service().recordImplicit(RecommendationActivityEvent.forOrganization("demo-student", "Kansai Career Tech"));

        ArgumentCaptor<RecommendationFeedbackEntity> saved = ArgumentCaptor.forClass(RecommendationFeedbackEntity.class);
        verify(feedbackRepository).save(saved.capture());
        assertEquals(58L, saved.getValue().getEventId());
        assertEquals(RecommendationFeedbackType.FOLLOWED, saved.getValue().getFeedbackType());
    }

    // ---- evaluation candidates ----

    @Test
    void notRelevantProposesAnEvaluationCase() {
        RecommendationImpressionEntity impression = impression(5L, 58L);
        impression.setNeedText("Spring Boot 线下活动");
        when(impressionRepository.findFirstByRunIdAndUserIdAndEventId(77L, "demo-student", 58L)).thenReturn(Optional.of(impression));
        when(feedbackRepository.findByImpressionIdAndExplicitFeedbackTrue(5L)).thenReturn(List.of());
        when(evalCandidateRepository.findByImpressionId(5L)).thenReturn(Optional.empty());

        service().submit("demo-student", new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, RecommendationFeedbackType.NOT_RELEVANT, "太远"));

        ArgumentCaptor<EvalCandidateEntity> saved = ArgumentCaptor.forClass(EvalCandidateEntity.class);
        verify(evalCandidateRepository).save(saved.capture());
        assertEquals(58L, saved.getValue().getEventId());
        assertEquals("Spring Boot 线下活动", saved.getValue().getNeedText());
        assertEquals("太远", saved.getValue().getReason());
        assertEquals(EvalCandidateStatus.PENDING, saved.getValue().getStatus());
    }

    @Test
    void takingItBackWithdrawsAPendingCandidateButNotAReviewedOne() {
        RecommendationImpressionEntity impression = impression(5L, 58L);
        when(impressionRepository.findFirstByRunIdAndUserIdAndEventId(77L, "demo-student", 58L)).thenReturn(Optional.of(impression));
        when(feedbackRepository.findByImpressionIdAndExplicitFeedbackTrue(5L)).thenReturn(List.of());
        EvalCandidateEntity pending = EvalCandidateEntity.from(impression, "太远");
        when(evalCandidateRepository.findByImpressionId(5L)).thenReturn(Optional.of(pending));

        service().submit("demo-student", new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, null, null));
        verify(evalCandidateRepository).delete(pending);

        EvalCandidateEntity reviewed = EvalCandidateEntity.from(impression, "太远");
        reviewed.review(EvalCandidateStatus.APPROVED, null, null, "ops");
        when(evalCandidateRepository.findByImpressionId(5L)).thenReturn(Optional.of(reviewed));
        service().submit("demo-student", new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, null, null));
        verify(evalCandidateRepository, never()).delete(reviewed);
    }

    // ---- profile ----

    @Test
    void feedbackMarksTheProfileDirty() {
        when(impressionRepository.findFirstByRunIdAndUserIdAndEventId(77L, "demo-student", 58L))
                .thenReturn(Optional.of(impression(5L, 58L)));
        when(feedbackRepository.findByImpressionIdAndExplicitFeedbackTrue(5L)).thenReturn(List.of());

        service().submit("demo-student", new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, RecommendationFeedbackType.INTERESTED, null));

        verify(profileDirtyService).markDirty("demo-student", "recommendation-feedback");
    }

    @Test
    void profileRefreshFailureDoesNotLoseTheFeedback() {
        when(impressionRepository.findFirstByRunIdAndUserIdAndEventId(77L, "demo-student", 58L))
                .thenReturn(Optional.of(impression(5L, 58L)));
        when(feedbackRepository.findByImpressionIdAndExplicitFeedbackTrue(5L)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox down"))
                .when(profileDirtyService).markDirty(any(), any());

        RecommendationFeedbackDtos.FeedbackState state = service().submit("demo-student",
                new RecommendationFeedbackDtos.SubmitRequest(77L, 58L, RecommendationFeedbackType.NOT_RELEVANT, "太远"));

        assertEquals(RecommendationFeedbackType.NOT_RELEVANT, state.type());
        verify(feedbackRepository).save(any());
    }

    @Test
    void recentSignalsCarryEventContextForTheProfileAgent() {
        RecommendationFeedbackEntity rejected = feedback(1L, 58L, RecommendationFeedbackType.NOT_RELEVANT);
        ReflectionTestUtils.setField(rejected, "reason", "时间对不上");
        when(feedbackRepository.findTop40ByUserIdAndUpdatedAtAfterOrderByUpdatedAtDesc(eq("demo-student"), any(Instant.class)))
                .thenReturn(List.of(rejected));
        EventEntity event = EventEntity.detached(58L);
        event.setTitle("后端工程实战夜");
        event.setCategory(EventCategory.COMPANY);
        event.setLocation("大阪梅田");
        when(eventRepository.findAllById(List.of(58L))).thenReturn(List.of(event));

        List<RecommendationFeedbackDtos.FeedbackSignal> signals = service().recentSignals("demo-student");

        assertEquals(1, signals.size());
        assertEquals("后端工程实战夜", signals.get(0).eventTitle());
        assertEquals("大阪梅田", signals.get(0).location());
        assertEquals("时间对不上", signals.get(0).reason());
        assertEquals(false, signals.get(0).positive());
    }

    @Test
    void listenerSwallowsFailuresSoTheBusinessActionIsUnaffected() {
        RecommendationFeedbackService failing = mock(RecommendationFeedbackService.class);
        when(failing.recordImplicit(any())).thenThrow(new IllegalStateException("db down"));

        new RecommendationActivityListener(failing).onActivity(
                RecommendationActivityEvent.forEvent("demo-student", 58L, RecommendationFeedbackType.RESERVED));

        verify(failing).recordImplicit(any());
    }

    private RecommendationFeedbackService service() {
        return new RecommendationFeedbackService(impressionRepository, feedbackRepository, eventRepository, evalCandidateRepository, profileDirtyService, 7, 90);
    }

    private static RecommendationImpressionEntity impression(Long id, Long eventId) {
        RecommendationImpressionEntity impression = new RecommendationImpressionEntity();
        ReflectionTestUtils.setField(impression, "id", id);
        impression.setUserId("demo-student");
        impression.setEventId(eventId);
        impression.setShownAt(Instant.now());
        return impression;
    }

    private static RecommendationFeedbackEntity feedback(Long id, Long eventId, RecommendationFeedbackType type) {
        RecommendationFeedbackEntity feedback = RecommendationFeedbackEntity.of(impression(id, eventId), type, null);
        ReflectionTestUtils.setField(feedback, "id", id);
        return feedback;
    }

    private static AiDtos.RecommendedEvent recommended(Long eventId, int score) {
        EventEntity entity = EventEntity.detached(eventId);
        entity.setTitle("活动 " + eventId);
        entity.setCategory(EventCategory.COMPANY);
        entity.setBenefitType(BenefitType.SKILL);
        entity.setStartTime(LocalDateTime.of(2026, 10, 30, 19, 0));
        entity.setEndTime(LocalDateTime.of(2026, 10, 30, 21, 0));
        return new AiDtos.RecommendedEvent(EventDtos.EventResponse.from(entity), score, "high", List.of("evidence"), "reason");
    }
}
