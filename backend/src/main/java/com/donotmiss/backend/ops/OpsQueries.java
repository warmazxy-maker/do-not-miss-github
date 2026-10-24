package com.donotmiss.backend.ops;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.agentlog.AgentRunEntity;
import com.donotmiss.backend.agentlog.AgentRunStatus;
import com.donotmiss.backend.agentlog.AgentRunStepEntity;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.mq.DomainEventOutboxEntity;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.mq.DomainEventOutboxStatus;
import com.donotmiss.backend.recommendation.RecommendationFeedbackEntity;
import com.donotmiss.backend.recommendation.RecommendationImpressionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewQuestionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewScoreSummaryEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewSessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Cross-user read queries of the operations console. Kept in JPQL rather than raw SQL so timestamps
 * are bound and read exactly the way the entities wrote them.
 */
public interface OpsQueries extends Repository<AgentRunEntity, Long> {

    // ---- Agent runs ----

    @Query("""
            select run from AgentRunEntity run
            where (:runType is null or run.runType = :runType)
              and (:status is null or run.status = :status)
              and (:userId is null or run.userId = :userId)
            order by run.id desc
            """)
    List<AgentRunEntity> runs(@Param("runType") AgentRunType runType,
                              @Param("status") AgentRunStatus status,
                              @Param("userId") String userId,
                              Pageable pageable);

    @Query("select run from AgentRunEntity run where run.startedAt >= :since")
    List<AgentRunEntity> runsSince(@Param("since") Instant since);

    @Query("select step from AgentRunStepEntity step where step.runId in :runIds")
    List<AgentRunStepEntity> stepsOf(@Param("runIds") Collection<Long> runIds);

    /** Runs whose input summary starts with a correlation prefix such as {@code recordId=} or {@code sessionId=}. */
    @Query("""
            select run from AgentRunEntity run
            where run.userId = :userId and run.runType in :runTypes and run.inputSummary like :prefix
            order by run.id desc
            """)
    List<AgentRunEntity> runsWithInputPrefix(@Param("userId") String userId,
                                             @Param("runTypes") Collection<AgentRunType> runTypes,
                                             @Param("prefix") String prefix,
                                             Pageable pageable);

    @Query("select count(run) from AgentRunEntity run where run.status = :status and run.startedAt < :before")
    long countRunsStartedBefore(@Param("status") AgentRunStatus status, @Param("before") Instant before);

    // ---- Outbox ----

    @Query("""
            select event from DomainEventOutboxEntity event
            where (:status is null or event.status = :status)
              and (:routingKey is null or event.routingKey = :routingKey)
            order by event.id desc
            """)
    List<DomainEventOutboxEntity> outbox(@Param("status") DomainEventOutboxStatus status,
                                         @Param("routingKey") String routingKey,
                                         Pageable pageable);

    @Query("""
            select event from DomainEventOutboxEntity event
            where event.status <> com.donotmiss.backend.mq.DomainEventOutboxStatus.SENT
              and (event.attempts >= :maxAttempts or event.nextAttemptAt > :now)
            order by event.id desc
            """)
    List<DomainEventOutboxEntity> stuckOutbox(@Param("maxAttempts") int maxAttempts,
                                              @Param("now") Instant now,
                                              Pageable pageable);

    @Query("""
            select event.status, event.routingKey, count(event)
            from DomainEventOutboxEntity event
            group by event.status, event.routingKey
            """)
    List<Object[]> outboxCounts();

    @Query("""
            select count(event) from DomainEventOutboxEntity event
            where event.status <> com.donotmiss.backend.mq.DomainEventOutboxStatus.SENT
              and event.attempts < :maxAttempts and event.nextAttemptAt > :now
            """)
    long countDeferred(@Param("maxAttempts") int maxAttempts, @Param("now") Instant now);

    @Query("""
            select count(event) from DomainEventOutboxEntity event
            where event.status <> com.donotmiss.backend.mq.DomainEventOutboxStatus.SENT
              and event.attempts >= :maxAttempts
            """)
    long countExhausted(@Param("maxAttempts") int maxAttempts);

    /** MySQL prints JSON as {"key": value, ...}, so a record id is followed by "," or "}". */
    @Query("""
            select event from DomainEventOutboxEntity event
            where event.payloadJson like :commaPattern or event.payloadJson like :bracePattern
            order by event.id asc
            """)
    List<DomainEventOutboxEntity> outboxMatching(@Param("commaPattern") String commaPattern,
                                                 @Param("bracePattern") String bracePattern);

    // ---- Records, jobs, evidence, users ----

    @Query("select record from AchievementRecordEntity record where record.userId = :userId order by record.completedAt desc")
    List<AchievementRecordEntity> records(@Param("userId") String userId, Pageable pageable);

    @Query("select job from AbilityAssessmentJobEntity job where job.achievementRecordId in :recordIds order by job.id asc")
    List<AbilityAssessmentJobEntity> jobsOf(@Param("recordIds") Collection<Long> recordIds);

    @Query("""
            select link.agentRunId, count(link) from CapabilityEvidenceLinkEntity link
            where link.agentRunId in :runIds
            group by link.agentRunId
            """)
    List<Object[]> evidenceLinkCounts(@Param("runIds") Collection<Long> runIds);

    @Query("""
            select count(job) from AbilityAssessmentJobEntity job
            where job.status = com.donotmiss.backend.abilityscore.AbilityAssessmentJobStatus.FAILED
            """)
    long countFailedJobs();

    // ---- Recommendations ----

    @Query("select run from AgentRunEntity run where run.runType = :runType and run.startedAt >= :since order by run.id desc")
    List<AgentRunEntity> runsOfTypeSince(@Param("runType") AgentRunType runType, @Param("since") Instant since);

    @Query("select impression from RecommendationImpressionEntity impression where impression.shownAt >= :since")
    List<RecommendationImpressionEntity> impressionsSince(@Param("since") Instant since);

    @Query("select impression from RecommendationImpressionEntity impression where impression.runId in :runIds")
    List<RecommendationImpressionEntity> impressionsOfRuns(@Param("runIds") Collection<Long> runIds);

    @Query("select feedback from RecommendationFeedbackEntity feedback where feedback.impressionId in :impressionIds")
    List<RecommendationFeedbackEntity> feedbackOf(@Param("impressionIds") Collection<Long> impressionIds);

    @Query("""
            select count(artifact) from AgentTraceArtifactEntity artifact
            where artifact.runId in :runIds and artifact.artifactType = :artifactType
            """)
    long countArtifacts(@Param("runIds") Collection<Long> runIds, @Param("artifactType") String artifactType);

    @Query("select event from EventEntity event where event.id in :ids")
    List<EventEntity> eventsByIds(@Param("ids") Collection<Long> ids);

    // ---- Resume interviews ----

    @Query("select session from ResumeInterviewSessionEntity session where session.userId = :userId order by session.id desc")
    List<ResumeInterviewSessionEntity> resumeSessions(@Param("userId") String userId, Pageable pageable);

    @Query("select question from ResumeInterviewQuestionEntity question where question.sessionId in :sessionIds")
    List<ResumeInterviewQuestionEntity> resumeQuestionsOf(@Param("sessionIds") Collection<Long> sessionIds);

    @Query("select evidence from ResumeInterviewEvidenceEntity evidence where evidence.sessionId in :sessionIds order by evidence.id asc")
    List<ResumeInterviewEvidenceEntity> resumeEvidenceOf(@Param("sessionIds") Collection<Long> sessionIds);

    @Query("select summary from ResumeInterviewScoreSummaryEntity summary where summary.sessionId in :sessionIds")
    List<ResumeInterviewScoreSummaryEntity> resumeScoresOf(@Param("sessionIds") Collection<Long> sessionIds);

    @Query("select user from UserEntity user order by user.id asc")
    List<UserEntity> users();

    @Query("select count(user) from UserEntity user")
    long countUsers();
}
