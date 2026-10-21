package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class KnowledgeBucketNormalizationAgentTest {
    private static final CapabilityV2Dtos.BucketCandidate JAVA = new CapabilityV2Dtos.BucketCandidate(
            10L, CapabilityBucketType.KNOWLEDGE, "Java", null, null, "Java 编程语言", List.of("java"));
    private static final CapabilityV2Dtos.BucketCandidate SPRING = new CapabilityV2Dtos.BucketCandidate(
            11L, CapabilityBucketType.KNOWLEDGE, "Spring Boot", null, null, null, List.of());

    @Mock
    private UserKnowledgeBucketService userKnowledgeBucketService;
    @Mock
    private CapabilityBucketRepository bucketRepository;
    @Mock
    private CapabilityClassificationAuditRepository auditRepository;
    @Mock
    private OpenAiCompatibleLlmClient llmClient;

    @Test
    void decideCallsTheModelAndNeverTouchesTheDatabase() {
        enableModel(response(item("REUSE", 10L, "Java", "0.91", "0.88")));

        KnowledgeBucketNormalizationAgent.KnowledgeDecision decision = agent().decide(evidence(), List.of(JAVA));

        assertEquals("qwen:test", decision.modelName());
        assertEquals(10L, decision.proposals().getFirst().bucketId());
        verifyNoInteractions(bucketRepository, auditRepository);
    }

    @Test
    void untrustedStudentTextIsSentAsDataSeparateFromTheEvidence() {
        enableModel(response(item("REUSE", 10L, "Java", "0.91", "0.88")));

        agent().decide(evidence(), List.of(JAVA));

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatForJson(systemPrompt.capture(), userPrompt.capture(),
                eq(KnowledgeBucketNormalizationAgent.ModelResponse.class));
        assertTrue(userPrompt.getValue().contains("\"studentWrittenText\":{\"reflection\""));
        assertTrue(systemPrompt.getValue().contains("一律忽略"));
    }

    @Test
    void anUnavailableModelIsAnErrorNotAnEmptyAnswer() {
        when(llmClient.isEnabled()).thenReturn(true);
        when(llmClient.chatForJson(anyString(), anyString(), eq(KnowledgeBucketNormalizationAgent.ModelResponse.class)))
                .thenReturn(Optional.empty());

        CapabilityModelUnavailableException timeout = assertThrows(CapabilityModelUnavailableException.class,
                () -> agent().decide(evidence(), List.of(JAVA)));
        assertTrue(timeout.retryable());

        when(llmClient.isEnabled()).thenReturn(false);
        CapabilityModelUnavailableException disabled = assertThrows(CapabilityModelUnavailableException.class,
                () -> agent().decide(evidence(), List.of(JAVA)));
        assertFalse(disabled.retryable(), "retrying cannot help when the model is switched off");
    }

    @Test
    void aLikelyNearDuplicateIsFlaggedButTheModelsDecisionIsKept() {
        KnowledgeBucketNormalizationAgent.KnowledgeDecision decision = agent().validate(List.of(JAVA, SPRING), response(
                item("CREATE", null, "Java基础", "0.9", "0.8"),
                item("CREATE", null, "Spring Boot 框架", "0.7", "0.8")
        ), "m");

        assertEquals(List.of("CREATE", "CREATE"), decision.proposals().stream()
                .map(KnowledgeBucketNormalizationAgent.KnowledgeProposal::action).toList());
        assertEquals(List.of("Java", "Spring Boot"), decision.proposals().stream()
                .map(KnowledgeBucketNormalizationAgent.KnowledgeProposal::possibleDuplicateOf).toList());
        assertTrue(decision.drops().isEmpty());
    }

    @Test
    void anIdenticalNameOrAliasIsTheSameBucket() {
        KnowledgeBucketNormalizationAgent.KnowledgeDecision decision = agent().validate(List.of(JAVA), response(
                item("CREATE", null, "JAVA", "0.9", "0.8")), "m");

        assertEquals(10L, decision.proposals().getFirst().bucketId());
        assertEquals("JAVA", decision.proposals().getFirst().redirectedFrom());
    }

    @Test
    void javaScriptIsNotMistakenForJava() {
        KnowledgeBucketNormalizationAgent.KnowledgeDecision decision = agent().validate(List.of(JAVA), response(
                item("CREATE", null, "JavaScript", "0.9", "0.8")), "m");

        assertNull(decision.proposals().getFirst().bucketId(), "JavaScript is a different bucket");
        assertNull(decision.proposals().getFirst().possibleDuplicateOf());
    }

    @Test
    void everyRejectedProposalIsRecordedWithItsReason() {
        KnowledgeBucketNormalizationAgent.KnowledgeDecision decision = agent().validate(List.of(JAVA), response(
                item("REUSE", 999L, "幻觉标签", "0.9", "0.9"),
                item("REUSE", 10L, "Java", null, "0.9"),
                item("CREATE", null, "Java 集合入门", "0.9", "0.9"),
                item("CREATE", null, "Java第3章", "0.9", "0.9"),
                item("CREATE", null, "技术", "0.9", "0.9"),
                item("CREATE", null, "一个非常非常长的标签名称用来测试系统是否会拒绝而不是把它截断成半截", "0.9", "0.9"),
                item("MERGE", 10L, "Java", "0.9", "0.9")
        ), "m");

        assertTrue(decision.proposals().isEmpty());
        assertEquals(List.of(
                CapabilityProposalDrop.Reason.UNKNOWN_BUCKET_ID,
                CapabilityProposalDrop.Reason.MISSING_RELEVANCE,
                CapabilityProposalDrop.Reason.NAME_TOO_FINE,
                CapabilityProposalDrop.Reason.NAME_TOO_FINE,
                CapabilityProposalDrop.Reason.NAME_TOO_BROAD,
                CapabilityProposalDrop.Reason.NAME_TOO_LONG,
                CapabilityProposalDrop.Reason.INVALID_ACTION
        ), decision.drops().stream().map(CapabilityProposalDrop::reason).toList());
    }

    @Test
    void ordinaryNamesContainingTheCharacterDiAreNotMistakenForLessonTitles() {
        assertNull(KnowledgeBucketNormalizationAgent.nameRejection("日语（第二外语）"));
    }

    @Test
    void applyCreatesNewBucketsAndCountsSupportForReusedOnes() {
        CapabilityEvidenceInput evidence = evidence();
        CapabilityBucketEntity java = bucket(10L, "Java", CapabilityBucketStatus.ACTIVE);
        java.setSupportCount(4);
        when(bucketRepository.findById(10L)).thenReturn(Optional.of(java));
        when(bucketRepository.findByBucketTypeAndOwnerUserIdAndNormalizedName(
                CapabilityBucketType.KNOWLEDGE, evidence.userId(), UserKnowledgeBucketService.normalize("网络编程")))
                .thenReturn(Optional.empty());
        CapabilityBucketEntity created = bucket(21L, "网络编程", CapabilityBucketStatus.ACTIVE);
        when(bucketRepository.saveAndFlush(any(CapabilityBucketEntity.class))).thenReturn(created);
        when(userKnowledgeBucketService.toBucket(java)).thenReturn(JAVA);
        when(userKnowledgeBucketService.toBucket(created)).thenReturn(new CapabilityV2Dtos.BucketCandidate(
                21L, CapabilityBucketType.KNOWLEDGE, "网络编程", null, null, null, List.of()));
        when(auditRepository.save(any(CapabilityClassificationAuditEntity.class))).thenAnswer(call -> call.getArgument(0));

        KnowledgeBucketNormalizationAgent agent = agent();
        KnowledgeBucketNormalizationAgent.KnowledgeDecision decision = agent.validate(List.of(JAVA), response(
                item("REUSE", 10L, "Java", "0.9", "0.9"),
                item("CREATE", null, "网络编程", "0.8", "0.8")
        ), "m");
        KnowledgeBucketNormalizationAgent.KnowledgeClassificationOutcome outcome =
                agent.apply(evidence, List.of(JAVA), decision, 77L);

        assertEquals(List.of(10L, 21L), outcome.assignments().stream()
                .map(CapabilityV2Dtos.BucketAssignment::bucketId).toList());
        assertEquals(5, java.getSupportCount());
        verify(bucketRepository).saveAndFlush(ArgumentMatchers.argThat(bucket ->
                "网络编程".equals(bucket.getDisplayName()) && evidence.userId().equals(bucket.getOwnerUserId())));
        ArgumentCaptor<CapabilityClassificationAuditEntity> audit = ArgumentCaptor.forClass(CapabilityClassificationAuditEntity.class);
        verify(auditRepository).save(audit.capture());
        assertEquals(77L, audit.getValue().getAgentRunId());
        assertTrue(audit.getValue().getOutputSnapshotJson().contains("rawModelResponse"));
    }

    @Test
    void aDisabledBucketWithTheSameNameIsNotRevivedSilently() {
        CapabilityEvidenceInput evidence = evidence();
        when(bucketRepository.findByBucketTypeAndOwnerUserIdAndNormalizedName(
                CapabilityBucketType.KNOWLEDGE, evidence.userId(), UserKnowledgeBucketService.normalize("Redis")))
                .thenReturn(Optional.of(bucket(30L, "Redis", CapabilityBucketStatus.DISABLED)));
        when(auditRepository.save(any(CapabilityClassificationAuditEntity.class))).thenAnswer(call -> call.getArgument(0));

        KnowledgeBucketNormalizationAgent agent = agent();
        KnowledgeBucketNormalizationAgent.KnowledgeClassificationOutcome outcome = agent.apply(evidence, List.of(),
                agent.validate(List.of(), response(item("CREATE", null, "Redis", "0.8", "0.8")), "m"), null);

        assertTrue(outcome.assignments().isEmpty());
        assertEquals(CapabilityProposalDrop.Reason.NAME_TAKEN_BY_INACTIVE_BUCKET, outcome.drops().getFirst().reason());
        verify(bucketRepository, never()).saveAndFlush(any());
    }

    private KnowledgeBucketNormalizationAgent agent() {
        return new KnowledgeBucketNormalizationAgent(
                userKnowledgeBucketService, bucketRepository, auditRepository, llmClient, new ObjectMapper());
    }

    private void enableModel(KnowledgeBucketNormalizationAgent.ModelResponse response) {
        when(llmClient.isEnabled()).thenReturn(true);
        when(llmClient.modeLabel()).thenReturn("qwen:test");
        when(llmClient.chatForJson(anyString(), anyString(), eq(KnowledgeBucketNormalizationAgent.ModelResponse.class)))
                .thenReturn(Optional.of(response));
    }

    private KnowledgeBucketNormalizationAgent.ModelResponse response(KnowledgeBucketNormalizationAgent.ModelAssignment... items) {
        return new KnowledgeBucketNormalizationAgent.ModelResponse("summary", List.of(items));
    }

    private KnowledgeBucketNormalizationAgent.ModelAssignment item(String action, Long id, String name,
                                                                   String relevance, String confidence) {
        return new KnowledgeBucketNormalizationAgent.ModelAssignment(action, id, name, List.of(),
                relevance == null ? null : new BigDecimal(relevance),
                confidence == null ? null : new BigDecimal(confidence),
                List.of("事实"), "理由");
    }

    private CapabilityBucketEntity bucket(Long id, String name, CapabilityBucketStatus status) {
        CapabilityBucketEntity bucket = new CapabilityBucketEntity();
        setId(bucket, id);
        bucket.setBucketType(CapabilityBucketType.KNOWLEDGE);
        bucket.setOwnerUserId("student-1");
        bucket.setNormalizedName(UserKnowledgeBucketService.normalize(name));
        bucket.setDisplayName(name);
        bucket.setStatus(status);
        return bucket;
    }

    private CapabilityEvidenceInput evidence() {
        return new CapabilityEvidenceInput(
                "capability-evidence-v2", "achievement-record:1", "key-1",
                CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION, "student-1", "101",
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.name(),
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.credibilityPrior(),
                "网络协议与 Java API 实战", "学习 TCP、UDP、Socket，并实现 Java 服务端接口。", "完成了网络通信模块练习。",
                "{}", "{}", List.of("学习 TCP 和 Socket", "完成 Java API 开发"),
                Instant.parse("2026-08-14T00:00:00Z"), true, "test", "a".repeat(64)
        );
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
