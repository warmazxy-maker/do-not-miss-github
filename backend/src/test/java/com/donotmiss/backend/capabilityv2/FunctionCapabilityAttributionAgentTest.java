package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionCapabilityAttributionAgentTest {
    private static final CapabilityV2Dtos.BucketCandidate BACKEND = candidate(10L, "后端开发");
    private static final CapabilityV2Dtos.BucketCandidate DESIGN = candidate(11L, "系统设计");

    @Mock
    private FunctionBucketCandidateService candidateService;
    @Mock
    private CapabilityClassificationAuditRepository auditRepository;
    @Mock
    private OpenAiCompatibleLlmClient llmClient;

    @Test
    void selectsOnlyValidCandidatesAndRecordsWhyOthersWereDropped() {
        FunctionCapabilityAttributionAgent.FunctionDecision decision = agent().validate(
                List.of(match(BACKEND), match(DESIGN)),
                response(
                        assignment("SELECT", 10L, "PRIMARY", "0.9"),
                        assignment("SELECT", 999L, "SECONDARY", "0.8"),
                        assignment("SELECT", 11L, "PRIMARY", "0.7"),
                        assignment("SELECT", 11L, "LEAD", "0.7"),
                        assignment("SELECT", 11L, "SECONDARY", null)
                ),
                "m"
        );

        assertEquals(List.of(10L), decision.assignments().stream().map(CapabilityV2Dtos.BucketAssignment::bucketId).toList());
        assertEquals(CapabilityEvidenceRole.PRIMARY, decision.assignments().getFirst().role());
        assertEquals(List.of(
                CapabilityProposalDrop.Reason.UNKNOWN_BUCKET_ID,
                CapabilityProposalDrop.Reason.DUPLICATE_PRIMARY,
                CapabilityProposalDrop.Reason.INVALID_ROLE,
                CapabilityProposalDrop.Reason.MISSING_RELEVANCE
        ), decision.drops().stream().map(CapabilityProposalDrop::reason).toList());
    }

    @Test
    void coachLogsStopAtTheGateWithoutAModelCall() {
        CapabilityEvidenceInput evidence = coachLog();
        FunctionCapabilityAttributionAgent agent = agent();

        FunctionCapabilityAttributionAgent.DeliveryGate gate = agent.deliveryGate(evidence);
        FunctionCapabilityAttributionAgent.FunctionDecision decision = agent.decide(evidence, List.of(match(BACKEND)), gate);

        assertFalse(gate.passed());
        assertEquals("delivery-gate", decision.modelName());
        verify(llmClient, never()).chatForJson(anyString(), anyString(), any());
    }

    @Test
    void courseLearningIsNoLongerBlockedByKeywordsTheModelDecides() {
        // The old keyword gate blocked this; now the model, told that learning is not a job function, judges it.
        assertTrue(agent().deliveryGate(courseOnlyEvidence()).passed());
        assertTrue(agent().deliveryGate(deliveryEvidence()).passed());
    }

    @Test
    void anUnavailableModelIsAnErrorNotAnEmptyAnswer() {
        when(llmClient.isEnabled()).thenReturn(true);
        when(llmClient.chatForJson(anyString(), anyString(), eq(FunctionCapabilityAttributionAgent.ModelResponse.class)))
                .thenReturn(Optional.empty());
        FunctionCapabilityAttributionAgent agent = agent();
        CapabilityEvidenceInput evidence = deliveryEvidence();

        CapabilityModelUnavailableException exception = assertThrows(CapabilityModelUnavailableException.class,
                () -> agent.decide(evidence, List.of(match(BACKEND)), agent.deliveryGate(evidence)));
        assertTrue(exception.retryable());
    }

    @Test
    void applyWritesOneAuditLinkedToTheRun() {
        when(auditRepository.save(any(CapabilityClassificationAuditEntity.class))).thenAnswer(call -> call.getArgument(0));
        FunctionCapabilityAttributionAgent agent = agent();
        CapabilityEvidenceInput evidence = deliveryEvidence();
        FunctionCapabilityAttributionAgent.FunctionDecision decision = agent.validate(
                List.of(match(BACKEND)), response(assignment("SELECT", 10L, "PRIMARY", "0.9")), "m");

        FunctionCapabilityAttributionAgent.FunctionAttributionOutcome outcome = agent.apply(
                evidence, List.of(match(BACKEND)), agent.deliveryGate(evidence), decision, 42L);

        assertEquals(1, outcome.assignments().size());
        ArgumentCaptor<CapabilityClassificationAuditEntity> audit = ArgumentCaptor.forClass(CapabilityClassificationAuditEntity.class);
        verify(auditRepository).save(audit.capture());
        assertEquals(42L, audit.getValue().getAgentRunId());
        assertTrue(audit.getValue().getOutputSnapshotJson().contains("\"gate\""));
    }

    private FunctionCapabilityAttributionAgent agent() {
        return new FunctionCapabilityAttributionAgent(candidateService, auditRepository, llmClient, new ObjectMapper());
    }

    private FunctionCapabilityAttributionAgent.ModelResponse response(FunctionCapabilityAttributionAgent.ModelAssignment... items) {
        return new FunctionCapabilityAttributionAgent.ModelResponse("summary", List.of(items));
    }

    private FunctionCapabilityAttributionAgent.ModelAssignment assignment(String action, Long bucketId, String role,
                                                                          String relevance) {
        return new FunctionCapabilityAttributionAgent.ModelAssignment(
                action, bucketId, role,
                relevance == null ? null : new BigDecimal(relevance),
                new BigDecimal("0.80"),
                "实现并交付服务端接口",
                List.of("完成 Spring Boot 服务端接口实现"),
                List.of(),
                "真实交付证据"
        );
    }

    private FunctionBucketCandidateService.FunctionCandidateMatch match(CapabilityV2Dtos.BucketCandidate candidate) {
        return new FunctionBucketCandidateService.FunctionCandidateMatch(candidate, new BigDecimal("0.8500"), 100, List.of("接口设计"));
    }

    private static CapabilityV2Dtos.BucketCandidate candidate(Long id, String displayName) {
        return new CapabilityV2Dtos.BucketCandidate(
                id, CapabilityBucketType.FUNCTION, displayName, "软件研发", "后端开发",
                "服务端编程、接口设计和业务系统交付", List.of("接口设计", "服务端编程"));
    }

    private CapabilityEvidenceInput deliveryEvidence() {
        return evidence("Spring Boot 后端接口交付", "负责服务端接口实现、MySQL 数据访问与鉴权逻辑。",
                "独立完成接口设计和接口联调。", List.of("完成接口设计", "实现数据库访问和鉴权"));
    }

    private CapabilityEvidenceInput courseOnlyEvidence() {
        return evidence("Java 基础课程学习", "学习面向对象、集合与异常处理。", "了解了 Java 的基础语法。",
                List.of("学习 Java 基础知识"));
    }

    private CapabilityEvidenceInput coachLog() {
        return new CapabilityEvidenceInput(
                "capability-evidence-v2", "coach-log:1", "key-1",
                CapabilityEvidenceSourceKind.COACH_LOG, "student-1", "1",
                AbilityEvidenceSourceType.PRIVATE_PERSONAL_RECORD.name(),
                AbilityEvidenceSourceType.PRIVATE_PERSONAL_RECORD.credibilityPrior(),
                "今日复盘", "负责了接口开发", "", "{}", "{}", List.of(),
                Instant.parse("2026-08-14T00:00:00Z"), false, "test", "a".repeat(64));
    }

    private CapabilityEvidenceInput evidence(String title, String description, String reflection, List<String> facts) {
        return new CapabilityEvidenceInput(
                "capability-evidence-v2", "achievement-record:1", "key-1",
                CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION, "student-1", "101",
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.name(),
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.credibilityPrior(),
                title, description, reflection, "{}", "{}", facts,
                Instant.parse("2026-08-14T00:00:00Z"), true, "test", "a".repeat(64));
    }
}
