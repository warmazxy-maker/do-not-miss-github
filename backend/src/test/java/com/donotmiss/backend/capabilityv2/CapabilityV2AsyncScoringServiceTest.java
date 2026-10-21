package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.abilityscore.AbilityAssessmentJobRepository;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentEntity;
import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentRepository;
import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityV2AsyncScoringServiceTest {
    private static final Long RUN_ID = 500L;

    @Mock private CapabilityEvidenceInputService evidenceInputService;
    @Mock private UserKnowledgeBucketService userKnowledgeBucketService;
    @Mock private KnowledgeBucketNormalizationAgent knowledgeAgent;
    @Mock private FunctionCapabilityAttributionAgent functionAgent;
    @Mock private CapabilityV2ScoringService scoringService;
    @Mock private CapabilityV2PipelineWriter writer;
    @Mock private AbilityEvidenceAssessmentRepository assessmentRepository;
    @Mock private AbilityAssessmentJobRepository jobRepository;
    @Mock private AgentRunService agentRunService;
    @Mock private AgentTraceArtifactService artifactService;

    @Test
    void runsModelStepsFirstThenWritesOnceUnderOneTracedRun() {
        CapabilityEvidenceInput evidence = stubInputs();
        when(agentRunService.startRun(eq("student-1"), eq(AgentRunType.CAPABILITY_V2_PIPELINE), anyString(), anyString()))
                .thenReturn(RUN_ID);
        KnowledgeBucketNormalizationAgent.KnowledgeDecision knowledge = new KnowledgeBucketNormalizationAgent.KnowledgeDecision(
                "p", "m", "s", null, List.of(), List.of());
        FunctionCapabilityAttributionAgent.DeliveryGate gate = new FunctionCapabilityAttributionAgent.DeliveryGate(true, List.of("实现"), "ok");
        FunctionCapabilityAttributionAgent.FunctionDecision function = new FunctionCapabilityAttributionAgent.FunctionDecision(
                "m", "s", null, List.of(), List.of());
        when(userKnowledgeBucketService.loadForUser("student-1")).thenReturn(List.of());
        when(knowledgeAgent.decide(evidence, List.of())).thenReturn(knowledge);
        when(functionAgent.recall(evidence)).thenReturn(List.of());
        when(functionAgent.deliveryGate(evidence)).thenReturn(gate);
        when(functionAgent.decide(evidence, List.of(), gate)).thenReturn(function);
        when(writer.write(any())).thenReturn(new CapabilityV2PipelineWriter.WriteResult(false,
                new KnowledgeBucketNormalizationAgent.KnowledgeClassificationOutcome("k", List.of(),
                        List.of(assignment(11L, CapabilityBucketType.KNOWLEDGE, "Java")), "m", "s", List.of()),
                new FunctionCapabilityAttributionAgent.FunctionAttributionOutcome("f", List.of(),
                        List.of(assignment(22L, CapabilityBucketType.FUNCTION, "后端开发")), "m", "s", List.of()),
                new CapabilityV2ScoringService.ScoringOutcome(evidence.evidenceId(), 0, List.of())));

        CapabilityV2AsyncScoringService.ProcessingOutcome result = service().process(
                99L, "student-1", 71L, new BigDecimal("0.82"), new BigDecimal("0.91"));

        ArgumentCaptor<CapabilityV2PipelineWriter.WriteCommand> command = ArgumentCaptor.forClass(CapabilityV2PipelineWriter.WriteCommand.class);
        verify(writer).write(command.capture());
        assertEquals(RUN_ID, command.getValue().agentRunId());
        assertEquals(new BigDecimal("0.8200"), command.getValue().evidenceQuality());
        assertEquals(new BigDecimal("0.8200"), command.getValue().signals().completionQuality());
        assertEquals(knowledge, command.getValue().knowledgeDecision());
        assertFalse(result.skipped());
        assertEquals(RUN_ID, result.agentRunId());
        assertEquals(2, result.knowledgeAssignments() + result.functionAssignments());

        InOrder order = inOrder(agentRunService, knowledgeAgent, functionAgent, writer);
        order.verify(knowledgeAgent).decide(evidence, List.of());
        order.verify(functionAgent).decide(evidence, List.of(), gate);
        order.verify(writer).write(any());
        order.verify(agentRunService).finishRun(eq(RUN_ID), anyString());
        verify(agentRunService).startStep(RUN_ID, AgentStepName.KNOWLEDGE_NORMALIZATION,
                "prompt=" + KnowledgeBucketNormalizationAgent.PROMPT_VERSION);
    }

    @Test
    void aModelOutageFailsTheRunBeforeAnythingIsWritten() {
        CapabilityEvidenceInput evidence = stubInputs();
        when(agentRunService.startRun(anyString(), any(), anyString(), anyString())).thenReturn(RUN_ID);
        when(userKnowledgeBucketService.loadForUser("student-1")).thenReturn(List.of());
        CapabilityModelUnavailableException outage = CapabilityModelUnavailableException.noResponse("KNOWLEDGE_NORMALIZATION");
        when(knowledgeAgent.decide(evidence, List.of())).thenThrow(outage);

        assertThrows(CapabilityModelUnavailableException.class, () -> service().process(
                99L, "student-1", 71L, new BigDecimal("0.82"), BigDecimal.ONE));

        verifyNoInteractions(writer);
        verify(agentRunService).failRun(RUN_ID, outage);
    }

    @Test
    void duplicateBusinessEvidenceIsSkippedBeforeAnyModelCall() {
        CapabilityEvidenceInput evidence = evidence();
        when(evidenceInputService.fromAchievement(99L, "student-1")).thenReturn(evidence);
        when(assessmentRepository.findById(71L)).thenReturn(Optional.of(assessment(71L)));
        when(jobRepository.findById(501L)).thenReturn(Optional.of(job(99L, "student-1")));
        when(scoringService.hasRecordedBusinessEvidence(evidence)).thenReturn(true);

        CapabilityV2AsyncScoringService.ProcessingOutcome result = service().process(
                99L, "student-1", 71L, new BigDecimal("0.82"), BigDecimal.ONE);

        assertTrue(result.skipped());
        verify(knowledgeAgent, never()).decide(any(), any());
        verifyNoInteractions(writer, agentRunService);
    }

    private CapabilityEvidenceInput stubInputs() {
        CapabilityEvidenceInput evidence = evidence();
        when(evidenceInputService.fromAchievement(99L, "student-1")).thenReturn(evidence);
        when(assessmentRepository.findById(71L)).thenReturn(Optional.of(assessment(71L)));
        when(jobRepository.findById(501L)).thenReturn(Optional.of(job(99L, "student-1")));
        when(scoringService.hasRecordedBusinessEvidence(evidence)).thenReturn(false);
        return evidence;
    }

    private CapabilityV2AsyncScoringService service() {
        return new CapabilityV2AsyncScoringService(
                evidenceInputService, userKnowledgeBucketService, knowledgeAgent, functionAgent, scoringService,
                writer, assessmentRepository, jobRepository, agentRunService, artifactService, new ObjectMapper());
    }

    private AbilityEvidenceAssessmentEntity assessment(Long id) {
        AbilityEvidenceAssessmentEntity assessment = new AbilityEvidenceAssessmentEntity();
        setId(assessment, id);
        assessment.setJobId(501L);
        assessment.setActivityDifficulty(new BigDecimal("46"));
        assessment.setActivityDifficultyConfidence(new BigDecimal("0.88"));
        assessment.setCompletionQuality(new BigDecimal("0.82"));
        assessment.setCompletionQualityConfidence(new BigDecimal("0.80"));
        assessment.setPersonalContribution(new BigDecimal("0.75"));
        assessment.setPersonalContributionConfidence(new BigDecimal("0.77"));
        assessment.setAssessmentConfidence(new BigDecimal("0.79"));
        assessment.setEvidenceFindingsJson("[{\"evidenceType\":\"completion_record\",\"verificationStatus\":\"third_party_record\",\"quality\":0.72}]");
        assessment.setRiskFlagsJson("[\"SELF_REPORTED_REFLECTION\"]");
        return assessment;
    }

    private AbilityAssessmentJobEntity job(Long recordId, String userId) {
        AbilityAssessmentJobEntity job = new AbilityAssessmentJobEntity();
        job.setAchievementRecordId(recordId);
        job.setUserId(userId);
        return job;
    }

    private CapabilityEvidenceInput evidence() {
        return new CapabilityEvidenceInput(
                "capability-evidence-v2", "achievement-record:99", "mutable-hash-key",
                CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION, "student-1", "601",
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.name(),
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.credibilityPrior(),
                "Java 项目实践", "完成接口和数据库模块", "独立完成联调", "{}", "{}",
                List.of("完成后端接口"), Instant.parse("2026-08-14T00:00:00Z"),
                true, "完成活动", "a".repeat(64));
    }

    private CapabilityV2Dtos.BucketAssignment assignment(Long bucketId, CapabilityBucketType type, String name) {
        return new CapabilityV2Dtos.BucketAssignment(
                bucketId, type, name, new BigDecimal("0.80"), new BigDecimal("0.85"),
                type == CapabilityBucketType.FUNCTION ? CapabilityEvidenceRole.PRIMARY : CapabilityEvidenceRole.NONE,
                "完成相关交付", List.of("可验证交付"), List.of());
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
