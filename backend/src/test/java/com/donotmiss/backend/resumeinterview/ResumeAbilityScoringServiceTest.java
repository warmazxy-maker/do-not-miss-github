package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.abilityscore.AbilityAssessmentJobRepository;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentEntity;
import com.donotmiss.backend.abilityscore.AbilityScoringExecution;
import com.donotmiss.backend.abilityscore.AbilityScoringService;
import com.donotmiss.backend.abilityscore.EvidenceAssessmentDtos;
import com.donotmiss.backend.abilityscore.EvidenceAssessmentPersistenceService;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.mq.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeAbilityScoringServiceTest {
    private static final String USER = "student-1";

    @Mock private ResumeInterviewSessionRepository sessionRepository;
    @Mock private ResumeInterviewEvidenceRepository evidenceRepository;
    @Mock private ResumeInterviewScoreSummaryRepository scoreSummaryRepository;
    @Mock private ResumeInterviewStateService stateService;
    @Mock private ResumeInterviewService resumeInterviewService;
    @Mock private AbilityAssessmentJobRepository jobRepository;
    @Mock private EvidenceAssessmentPersistenceService persistenceService;
    @Mock private AbilityScoringService abilityScoringService;
    @Mock private AgentRunService agentRunService;
    @Mock private DomainEventPublisher publisher;
    @Mock private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void scoringAResumeEvidenceKeepsV1AndQueuesTheSameAssessmentForV2() throws Exception {
        ResumeInterviewEvidenceEntity candidate = candidate(ResumeInterviewEvidenceStatus.EVIDENCE_READY, "[]");
        stubSession(candidate);
        AbilityAssessmentJobEntity job = new AbilityAssessmentJobEntity();
        setId(job, 501L);
        when(jobRepository.findFirstByUserIdAndEvidenceHashAndPromptVersionAndRubricVersionOrderByCreatedAtDesc(
                eq(USER), eq("h".repeat(64)), anyString(), anyString())).thenReturn(Optional.of(job));
        AbilityEvidenceAssessmentEntity saved = new AbilityEvidenceAssessmentEntity();
        setId(saved, 71L);
        when(persistenceService.saveAssessment(eq(501L), any())).thenReturn(saved);
        when(abilityScoringService.score(any())).thenReturn(new AbilityScoringExecution(71L, "r", 0, 0, List.of()));

        service().score(USER, 7L);

        InOrder order = inOrder(abilityScoringService, evidenceRepository, publisher);
        order.verify(abilityScoringService).score(any());
        order.verify(evidenceRepository).save(candidate);
        // evidenceQuality = mean finding quality (0.6, 0.8); novelty = 0.7 because of GENERIC_ANSWER.
        order.verify(publisher).publishResumeCapabilityV2Scoring(
                31L, USER, 71L, new BigDecimal("0.7000"), new BigDecimal("0.7000"));
    }

    @Test
    void anAlreadyScoredEvidenceIsNotQueuedAgain() throws Exception {
        ResumeInterviewEvidenceEntity candidate = candidate(ResumeInterviewEvidenceStatus.SCORED, "[901]");
        stubSession(candidate);

        service().score(USER, 7L);

        verify(abilityScoringService, never()).score(any());
        verifyNoInteractions(publisher);
    }

    private void stubSession(ResumeInterviewEvidenceEntity candidate) {
        ResumeInterviewSessionEntity session = new ResumeInterviewSessionEntity();
        setId(session, 7L);
        session.setUserId(USER);
        session.setTargetRole("后端工程师");
        session.setStatus(ResumeInterviewSessionStatus.EVIDENCE_READY);
        ResumeInterviewScoreSummaryEntity summary = new ResumeInterviewScoreSummaryEntity();
        setId(summary, 3L);
        summary.setSessionId(7L);

        when(sessionRepository.findByIdAndUserId(7L, USER)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(scoreSummaryRepository.findBySessionId(7L)).thenReturn(Optional.of(summary));
        lenient().when(scoreSummaryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(scoreSummaryRepository.findById(3L)).thenReturn(Optional.of(summary));
        when(evidenceRepository.findBySessionIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(candidate));
        lenient().when(evidenceRepository.findById(31L)).thenReturn(Optional.of(candidate));
    }

    private ResumeInterviewEvidenceEntity candidate(ResumeInterviewEvidenceStatus status, String resultIds) throws Exception {
        EvidenceAssessmentDtos.ModelResponse assessment = new EvidenceAssessmentDtos.ModelResponse(
                "RESUME_INTERVIEW_ANSWER",
                new BigDecimal("55"), new BigDecimal("0.7"),
                new BigDecimal("0.6"), new BigDecimal("0.7"),
                new BigDecimal("0.8"), new BigDecimal("0.7"),
                new BigDecimal("0.7"),
                List.of(new EvidenceAssessmentDtos.DimensionItem("后端开发", "后端开发",
                        new BigDecimal("0.8"), new BigDecimal("0.7"), "实现了接口", List.of())),
                List.of(
                        new EvidenceAssessmentDtos.EvidenceFinding("e1", "answer", new BigDecimal("0.6"), "self_reported", List.of()),
                        new EvidenceAssessmentDtos.EvidenceFinding("e2", "resume", new BigDecimal("0.8"), "self_reported", List.of())),
                null,
                List.of("GENERIC_ANSWER"),
                null
        );
        ResumeInterviewDtos.UnifiedAbilityEvidence unified = new ResumeInterviewDtos.UnifiedAbilityEvidence(
                null, null, null, 7L, 1L, 11L, 12L, "h".repeat(64), assessment);

        ResumeInterviewEvidenceEntity candidate = new ResumeInterviewEvidenceEntity();
        setId(candidate, 31L);
        candidate.setSessionId(7L);
        candidate.setStatus(status);
        candidate.setAbilityEvidenceJson(objectMapper.writeValueAsString(unified));
        candidate.setAbilityScoreResultIdsJson(resultIds);
        return candidate;
    }

    private ResumeAbilityScoringService service() {
        return new ResumeAbilityScoringService(
                sessionRepository, evidenceRepository, scoreSummaryRepository, stateService, resumeInterviewService,
                jobRepository, persistenceService, abilityScoringService, agentRunService, publisher,
                objectMapper, transactionTemplate);
    }

    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
