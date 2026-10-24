package com.donotmiss.backend.ops;

import com.donotmiss.backend.agentlog.AgentRunEntity;
import com.donotmiss.backend.agentlog.AgentRunStatus;
import com.donotmiss.backend.recommendation.RecommendationFeedbackEntity;
import com.donotmiss.backend.recommendation.RecommendationFeedbackType;
import com.donotmiss.backend.recommendation.RecommendationImpressionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpsRecommendationServiceTest {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    void funnelCountsEachImpressionOncePerType() {
        List<RecommendationImpressionEntity> impressions = List.of(
                impression(1L, 58L, "2026-10-24T01:00:00Z"),
                impression(2L, 31L, "2026-10-24T01:00:00Z"),
                impression(3L, 12L, "2026-10-24T01:00:00Z"),
                impression(4L, 40L, "2026-10-24T01:00:00Z"));
        List<RecommendationFeedbackEntity> feedback = List.of(
                feedback(impressions.get(0), RecommendationFeedbackType.INTERESTED),
                feedback(impressions.get(0), RecommendationFeedbackType.RESERVED),
                feedback(impressions.get(0), RecommendationFeedbackType.COMPLETED),
                feedback(impressions.get(1), RecommendationFeedbackType.NOT_RELEVANT),
                feedback(impressions.get(2), RecommendationFeedbackType.RESERVED),
                // feedback on an impression outside the window is ignored
                feedback(impression(99L, 7L, "2026-09-01T01:00:00Z"), RecommendationFeedbackType.RESERVED));

        OpsDtos.RecommendationFunnel funnel = OpsRecommendationService.funnel(impressions, feedback);

        assertEquals(4, funnel.impressions());
        assertEquals(1, funnel.interested());
        assertEquals(1, funnel.notRelevant());
        assertEquals(2, funnel.reserved());
        assertEquals(1, funnel.completed());
        assertEquals(0.5, funnel.reserveRate());
        assertEquals(0.25, funnel.notRelevantRate());
        assertEquals(0.5, funnel.explicitFeedbackRate(), "two of four impressions got a click");
    }

    @Test
    void emptyWindowHasZeroRatesNotDivisionErrors() {
        OpsDtos.RecommendationFunnel funnel = OpsRecommendationService.funnel(List.of(), List.of());

        assertEquals(0, funnel.impressions());
        assertEquals(0.0, funnel.interestedRate());
    }

    @Test
    void dailyBucketsUseTheLocalDateAndTheDayTheRecommendationWasShown() {
        // 2026-10-23T16:30Z is 2026-10-24 01:30 in Tokyo
        RecommendationImpressionEntity late = impression(1L, 58L, "2026-10-23T16:30:00Z");
        RecommendationImpressionEntity early = impression(2L, 31L, "2026-10-23T01:00:00Z");
        List<AgentRunEntity> runs = List.of(
                run(AgentRunStatus.SUCCEEDED, "2026-10-23T16:30:00Z", "need. recommendationCount=1, eventIds=[58]"),
                run(AgentRunStatus.SUCCEEDED, "2026-10-23T01:00:00Z", "need. recommendationCount=0, eventIds=[]"),
                run(AgentRunStatus.FAILED, "2026-10-23T02:00:00Z", null));
        // reserved two days later still counts on the day the event was recommended
        RecommendationFeedbackEntity reserved = feedback(late, RecommendationFeedbackType.RESERVED);

        List<OpsDtos.RecommendationDay> days = OpsRecommendationService.daily(runs, List.of(late, early), List.of(reserved), TOKYO);

        assertEquals(2, days.size());
        assertEquals("2026-10-23", days.get(0).date());
        assertEquals(2, days.get(0).runs());
        assertEquals(1, days.get(0).zeroResultRuns());
        assertEquals("2026-10-24", days.get(1).date());
        assertEquals(1, days.get(1).impressions());
        assertEquals(1, days.get(1).reserved());
    }

    @Test
    void recommendationCountIsReadFromRunSummary() {
        assertEquals(Optional.of(0), OpsRecommendationService.recommendationCount("已检查 0 个候选 recommendationCount=0, eventIds=[]"));
        assertEquals(Optional.of(3), OpsRecommendationService.recommendationCount("x recommendationCount=3, eventIds=[1, 2, 3]"));
        assertEquals(Optional.empty(), OpsRecommendationService.recommendationCount(null));
    }

    private static RecommendationImpressionEntity impression(Long id, Long eventId, String shownAt) {
        RecommendationImpressionEntity impression = new RecommendationImpressionEntity();
        ReflectionTestUtils.setField(impression, "id", id);
        impression.setUserId("demo-student");
        impression.setEventId(eventId);
        impression.setRunId(160L);
        impression.setShownAt(Instant.parse(shownAt));
        return impression;
    }

    private static RecommendationFeedbackEntity feedback(RecommendationImpressionEntity impression, RecommendationFeedbackType type) {
        RecommendationFeedbackEntity feedback = RecommendationFeedbackEntity.of(impression, type, null);
        ReflectionTestUtils.setField(feedback, "updatedAt", Instant.parse("2026-10-25T00:00:00Z"));
        return feedback;
    }

    private static AgentRunEntity run(AgentRunStatus status, String startedAt, String output) {
        AgentRunEntity run = new AgentRunEntity();
        run.setStatus(status);
        run.setStartedAt(Instant.parse(startedAt));
        run.setOutputSummary(output);
        return run;
    }
}
