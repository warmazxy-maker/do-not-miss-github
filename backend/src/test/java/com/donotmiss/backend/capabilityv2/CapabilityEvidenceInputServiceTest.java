package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.achievement.AchievementSourceType;
import com.donotmiss.backend.coach.CoachLogEntity;
import com.donotmiss.backend.coach.CoachLogRepository;
import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.eventquality.EventQualityReportEntity;
import com.donotmiss.backend.eventquality.EventQualityReportRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewAnswerEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewAnswerRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceStatus;
import com.donotmiss.backend.resumeinterview.ResumeInterviewQuestionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewQuestionRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewSessionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityEvidenceInputServiceTest {
    @Mock
    private AchievementRecordRepository achievementRecordRepository;
    @Mock
    private CoachLogRepository coachLogRepository;
    @Mock
    private ResumeInterviewEvidenceRepository resumeInterviewEvidenceRepository;
    @Mock
    private ResumeInterviewSessionRepository resumeInterviewSessionRepository;
    @Mock
    private ResumeInterviewQuestionRepository resumeInterviewQuestionRepository;
    @Mock
    private ResumeInterviewAnswerRepository resumeInterviewAnswerRepository;
    @Mock
    private EventQualityReportRepository eventQualityReportRepository;

    @Test
    void adaptsCompletedActivityWithQualityReportIntoScoreCandidate() {
        AchievementRecordEntity record = activityRecord();
        EventQualityReportEntity qualityReport = new EventQualityReportEntity();
        qualityReport.setEventId(101L);
        qualityReport.setQualityScore(92);
        qualityReport.setQualityLevel("HIGH");
        qualityReport.setReviewSuggestion("APPROVE");
        qualityReport.setDifficulty("INTERMEDIATE");
        qualityReport.setModelName("qwen-test");

        when(achievementRecordRepository.findByIdAndUserId(1L, "student-1")).thenReturn(Optional.of(record));
        when(eventQualityReportRepository.findByEventId(101L)).thenReturn(Optional.of(qualityReport));

        CapabilityEvidenceInput input = service().fromAchievement(1L, "student-1");

        assertEquals(CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION, input.sourceKind());
        assertEquals("ORGANIZATION_ACTIVITY_RECORD", input.sourceType());
        assertTrue(input.scoringEligible());
        assertTrue(input.qualityReportJson().contains("\"qualityScore\":92"));
        assertTrue(input.reflection().contains("完成了接口开发"));
        assertTrue(input.idempotencyKey().startsWith("capability-v2:achievement-record:1:"));
        assertEquals(64, input.evidenceHash().length());
    }

    @Test
    void adaptsCompletedChallengeAsSelfReportedScoreCandidate() {
        AchievementRecordEntity record = activityRecord();
        record.setSourceType(AchievementSourceType.CHALLENGE);
        record.setSourceId(66L);
        record.setEventId(66L);
        record.setEventTitle("完成 Spring Boot 挑战");
        when(achievementRecordRepository.findByIdAndUserId(1L, "student-1")).thenReturn(Optional.of(record));

        CapabilityEvidenceInput input = service().fromAchievement(1L, "student-1");

        assertEquals(CapabilityEvidenceSourceKind.CHALLENGE_COMPLETION, input.sourceKind());
        assertEquals("SELF_REPORT", input.sourceType());
        assertEquals("66", input.sourceId());
        assertTrue(input.scoringEligible());
    }

    @Test
    void adaptsCoachLogAsContextOnlyInsteadOfSilentlyScoringIt() {
        CoachLogEntity log = new CoachLogEntity();
        setId(log, 9L);
        log.setUserId("student-1");
        log.setLogDate(LocalDate.of(2026, 8, 14));
        log.setTitle("Spring Boot 复盘");
        log.setSummary("梳理了事务传播行为");
        log.setContent("今天排查了一个事务失效问题，并复盘了传播行为。");
        log.setTags("Spring,事务");
        when(coachLogRepository.findById(9L)).thenReturn(Optional.of(log));

        CapabilityEvidenceInput input = service().fromCoachLog(9L, "student-1");

        assertEquals(CapabilityEvidenceSourceKind.COACH_LOG, input.sourceKind());
        assertEquals("PRIVATE_PERSONAL_RECORD", input.sourceType());
        assertFalse(input.scoringEligible());
        assertTrue(input.eligibilityReason().contains("关联完成事项"));
    }

    @Test
    void adaptsReadyResumeInterviewEvidenceWithoutBypassingUnifiedContract() {
        ResumeInterviewEvidenceEntity evidence = stubResume(ResumeInterviewEvidenceStatus.EVIDENCE_READY,
                "我用 LangChain 搭了 RAG 检索，负责向量召回和重排。");

        CapabilityEvidenceInput input = service().fromResumeInterviewEvidence(31L, "student-1");

        assertEquals(CapabilityEvidenceSourceKind.RESUME_INTERVIEW, input.sourceKind());
        assertEquals("RESUME_INTERVIEW", input.sourceType());
        assertEquals(AbilityEvidenceSourceType.RESUME_INTERVIEW.credibilityPrior(), input.sourceCredibilityPrior());
        assertEquals("31", input.sourceId());
        assertEquals("resume-interview-evidence:31", input.evidenceId());
        assertTrue(input.scoringEligible());
        assertTrue(input.structuredPayloadJson().contains("Agent 后端工程师"));
        // The question is system text; the answer is the student's own words and goes to reflection.
        assertEquals("面试问题：你在 RAG 项目里具体负责什么？", input.description());
        assertTrue(input.reflection().contains("向量召回"));
        assertTrue(input.evidenceFacts().contains("考察维度：RAG"));
        assertFalse(input.evidenceFacts().stream().anyMatch(fact -> fact.startsWith("证据状态")));

        // V1 scoring flips the status to SCORED; the V2 evidence must stay the same evidence.
        evidence.setStatus(ResumeInterviewEvidenceStatus.SCORED);
        CapabilityEvidenceInput afterV1 = service().fromResumeInterviewEvidence(31L, "student-1");
        assertEquals(input.evidenceHash(), afterV1.evidenceHash());
        assertTrue(afterV1.scoringEligible());
    }

    @Test
    void resumeEvidenceWithoutAnAnswerIsNotScored() {
        stubResume(ResumeInterviewEvidenceStatus.EVIDENCE_READY, "  ");

        CapabilityEvidenceInput input = service().fromResumeInterviewEvidence(31L, "student-1");

        assertFalse(input.scoringEligible());
        assertTrue(input.eligibilityReason().contains("回答缺失"));
    }

    @Test
    void resumeEvidenceThatIsStillADraftIsNotScored() {
        stubResume(ResumeInterviewEvidenceStatus.DRAFT, "完整回答");

        CapabilityEvidenceInput input = service().fromResumeInterviewEvidence(31L, "student-1");

        assertFalse(input.scoringEligible());
        assertTrue(input.eligibilityReason().contains("尚未就绪"));
    }

    private ResumeInterviewEvidenceEntity stubResume(ResumeInterviewEvidenceStatus status, String answerText) {
        ResumeInterviewEvidenceEntity evidence = new ResumeInterviewEvidenceEntity();
        setId(evidence, 31L);
        evidence.setSessionId(7L);
        evidence.setQuestionId(11L);
        evidence.setAnswerId(12L);
        evidence.setSourceType(AbilityEvidenceSourceType.RESUME_INTERVIEW);
        evidence.setStatus(status);
        evidence.setAbilityEvidenceJson("{\"ability\":\"RAG\"}");

        ResumeInterviewSessionEntity session = new ResumeInterviewSessionEntity();
        session.setUserId("student-1");
        session.setTargetRole("Agent 后端工程师");
        ResumeInterviewQuestionEntity question = new ResumeInterviewQuestionEntity();
        question.setQuestionText("你在 RAG 项目里具体负责什么？");
        question.setTargetDimension("RAG");
        ResumeInterviewAnswerEntity answer = new ResumeInterviewAnswerEntity();
        answer.setSessionId(7L);
        answer.setAnswerText(answerText);

        when(resumeInterviewEvidenceRepository.findById(31L)).thenReturn(Optional.of(evidence));
        when(resumeInterviewSessionRepository.findByIdAndUserId(7L, "student-1")).thenReturn(Optional.of(session));
        when(resumeInterviewQuestionRepository.findByIdAndSessionId(11L, 7L)).thenReturn(Optional.of(question));
        when(resumeInterviewAnswerRepository.findById(12L)).thenReturn(Optional.of(answer));
        return evidence;
    }

    private CapabilityEvidenceInputService service() {
        return new CapabilityEvidenceInputService(
                achievementRecordRepository,
                coachLogRepository,
                resumeInterviewEvidenceRepository,
                resumeInterviewSessionRepository,
                resumeInterviewQuestionRepository,
                resumeInterviewAnswerRepository,
                eventQualityReportRepository,
                new ObjectMapper()
        );
    }

    private AchievementRecordEntity activityRecord() {
        AchievementRecordEntity record = new AchievementRecordEntity();
        setId(record, 1L);
        record.setUserId("student-1");
        record.setSourceType(AchievementSourceType.EVENT);
        record.setSourceId(101L);
        record.setEventId(101L);
        record.setEventTitle("Spring Boot API 实战");
        record.setOrganizationName("Campus Lab");
        record.setCategory("企业");
        record.setEventStartTime(LocalDateTime.of(2026, 8, 10, 10, 0));
        record.setLocation("线上");
        record.setContent("完成一个带鉴权的 REST API。");
        record.setBenefitType(BenefitType.SKILL);
        record.setSkill("Spring Boot, REST API");
        record.setCompletedAt(Instant.parse("2026-08-11T01:00:00Z"));
        record.setDid("完成了接口开发");
        record.setLearned("学会了事务边界设计");
        return record;
    }

    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
