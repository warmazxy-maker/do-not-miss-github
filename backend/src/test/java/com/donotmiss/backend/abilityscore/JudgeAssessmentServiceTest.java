package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgeAssessmentServiceTest {
    @Mock
    private JudgeAssessmentRepository judgeRepository;
    @Mock
    private AbilityScoreResultRepository resultRepository;
    @Mock
    private UserAbilityStateRepository stateRepository;
    @Mock
    private AbilityScoringProfileRepository profileRepository;
    @Mock
    private AbilityEvidenceDimensionRepository dimensionRepository;
    @Mock
    private AchievementRecordRepository recordRepository;
    @Mock
    private OpenAiCompatibleLlmClient llmClient;
    @Mock
    private AgentRunService agentRunService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void createsOnePendingJudgeTaskForReviewRequiredResult() {
        AbilityScoreResultEntity result = result(11L, AbilityScoreResultStatus.REVIEW_REQUIRED);
        UserAbilityStateEntity state = state(7L, "DEVELOPING");
        AbilityScoringProfileEntity profile = profile("student-1", "0.5000");

        when(judgeRepository.findByScoreResultId(11L)).thenReturn(Optional.empty());
        when(stateRepository.findById(7L)).thenReturn(Optional.of(state));
        when(profileRepository.findById("student-1")).thenReturn(Optional.of(profile));
        when(judgeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(llmClient.modeLabel()).thenReturn("qwen:qwen-plus");

        JudgeAssessmentService service = service();
        JudgeAssessmentEntity created = service.createIfRequired(
                result,
                List.of("RANK_PROMOTION_REQUIRES_JUDGE")
        );

        assertNotNull(created);
        assertEquals(JudgeAssessmentStatus.PENDING, created.getStatus());
        assertEquals(JudgeDecision.PENDING, created.getDecision());
        assertEquals(11L, created.getScoreResultId());
        assertEquals(new BigDecimal("0.5000"), created.getConfidenceBefore());
    }

    @Test
    void passingJudgeConfirmsRankAndRaisesConfidenceWithinBound() {
        AbilityScoreResultEntity result = result(11L, AbilityScoreResultStatus.REVIEW_REQUIRED);
        UserAbilityStateEntity state = state(7L, "DEVELOPING");
        AbilityScoringProfileEntity profile = profile("student-1", "0.5000");
        JudgeAssessmentEntity judge = inProgressJudge(5L, result, state, profile);

        when(judgeRepository.findById(5L)).thenReturn(Optional.of(judge));
        when(resultRepository.findById(11L)).thenReturn(Optional.of(result));
        when(stateRepository.findById(7L)).thenReturn(Optional.of(state));
        when(profileRepository.findById("student-1")).thenReturn(Optional.of(profile));
        when(dimensionRepository.findByAssessmentIdOrderByRelevanceDesc(3L)).thenReturn(List.of());
        when(recordRepository.findById(9L)).thenReturn(Optional.empty());
        when(agentRunService.startRun(anyString(), any(), anyString(), anyString())).thenReturn(100L);
        when(agentRunService.startStep(any(), any(), anyString())).thenReturn(101L);
        when(llmClient.modeLabel()).thenReturn("qwen:qwen-plus");
        when(llmClient.chatForJson(anyString(), anyString(), eq(JudgeDtos.EvaluationModelResponse.class)))
                .thenReturn(Optional.of(new JudgeDtos.EvaluationModelResponse(
                        List.of(
                                rubric("q1", 9),
                                rubric("q2", 8),
                                rubric("q3", 9)
                        ),
                        "回答能够说明本人贡献、核心原理和排错方法。"
                )));
        when(judgeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(profileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JudgeAssessmentService service = service();
        JudgeDtos.AssessmentResponse response = service.submit(
                "student-1",
                5L,
                new JudgeDtos.SubmitRequest(List.of(
                        new JudgeDtos.Answer("q1", "我负责接口设计、编码、测试，并保留了提交记录。"),
                        new JudgeDtos.Answer("q2", "核心是通过事务边界保证业务状态与事件消息一致。"),
                        new JudgeDtos.Answer("q3", "我会先复现问题，再根据日志和指标缩小范围并验证假设。")
                ))
        );

        assertEquals("PASS", response.decision());
        assertEquals("PROFICIENT", state.getRankName());
        assertEquals(AbilityScoreResultStatus.VERIFIED, result.getStatus());
        assertEquals(new BigDecimal("0.5370"), profile.getProfileConfidence());
        assertEquals(87, response.rubric().totalScore());
    }

    @Test
    void missingJudgeModelRoutesToManualReviewWithoutChangingProfile() {
        AbilityScoreResultEntity result = result(11L, AbilityScoreResultStatus.REVIEW_REQUIRED);
        UserAbilityStateEntity state = state(7L, "DEVELOPING");
        AbilityScoringProfileEntity profile = profile("student-1", "0.5000");
        JudgeAssessmentEntity judge = inProgressJudge(5L, result, state, profile);

        when(judgeRepository.findById(5L)).thenReturn(Optional.of(judge));
        when(resultRepository.findById(11L)).thenReturn(Optional.of(result));
        when(stateRepository.findById(7L)).thenReturn(Optional.of(state));
        when(profileRepository.findById("student-1")).thenReturn(Optional.of(profile));
        when(dimensionRepository.findByAssessmentIdOrderByRelevanceDesc(3L)).thenReturn(List.of());
        when(recordRepository.findById(9L)).thenReturn(Optional.empty());
        when(agentRunService.startRun(anyString(), any(), anyString(), anyString())).thenReturn(100L);
        when(agentRunService.startStep(any(), any(), anyString())).thenReturn(101L);
        when(llmClient.chatForJson(anyString(), anyString(), eq(JudgeDtos.EvaluationModelResponse.class)))
                .thenReturn(Optional.empty());
        when(judgeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JudgeAssessmentService service = service();
        JudgeDtos.AssessmentResponse response = service.submit(
                "student-1",
                5L,
                new JudgeDtos.SubmitRequest(List.of(
                        new JudgeDtos.Answer("q1", "我完成了具体模块并保留了相关记录用于核对。"),
                        new JudgeDtos.Answer("q2", "我可以解释实际使用的方法及其选择原因。"),
                        new JudgeDtos.Answer("q3", "我会复现、收集证据、提出假设并逐项验证。")
                ))
        );

        assertEquals("MANUAL_REVIEW", response.decision());
        assertEquals(new BigDecimal("0.0000"), response.confidenceDelta());
        assertEquals("DEVELOPING", state.getRankName());
        assertEquals(AbilityScoreResultStatus.REVIEW_REQUIRED, result.getStatus());
        verify(profileRepository, never()).save(any());
    }

    private JudgeAssessmentService service() {
        return new JudgeAssessmentService(
                judgeRepository,
                resultRepository,
                stateRepository,
                profileRepository,
                dimensionRepository,
                recordRepository,
                llmClient,
                new JudgeDecisionPolicy(),
                agentRunService,
                objectMapper
        );
    }

    private JudgeDtos.RubricItem rubric(String id, int score) {
        return new JudgeDtos.RubricItem(id, score, 10, "评分理由", List.of("回答证据"));
    }

    private AbilityScoreResultEntity result(Long id, AbilityScoreResultStatus status) {
        AbilityScoreResultEntity result = new AbilityScoreResultEntity();
        setId(result, id);
        result.setUserId("student-1");
        result.setAchievementRecordId(9L);
        result.setAssessmentId(3L);
        result.setAbilityStateId(7L);
        result.setDimensionName("Java 后端开发");
        result.setNormalizedDimension("java-backend");
        result.setStatus(status);
        result.setOldAbilityScore(new BigDecimal("45.0000"));
        result.setNewAbilityScore(new BigDecimal("52.0000"));
        result.setFactorSnapshotJson("{\"proposedRank\":\"PROFICIENT\"}");
        result.setJudgeFlagsJson("[\"RANK_PROMOTION_REQUIRES_JUDGE\"]");
        return result;
    }

    private UserAbilityStateEntity state(Long id, String rank) {
        UserAbilityStateEntity state = new UserAbilityStateEntity();
        setId(state, id);
        state.setUserId("student-1");
        state.setDimensionName("Java 后端开发");
        state.setNormalizedDimension("java-backend");
        state.setRankName(rank);
        state.setAbilityScore(new BigDecimal("52.0000"));
        return state;
    }

    private AbilityScoringProfileEntity profile(String userId, String confidence) {
        AbilityScoringProfileEntity profile = new AbilityScoringProfileEntity();
        profile.setUserId(userId);
        profile.setProfileConfidence(new BigDecimal(confidence));
        return profile;
    }

    private JudgeAssessmentEntity inProgressJudge(Long id,
                                                  AbilityScoreResultEntity result,
                                                  UserAbilityStateEntity state,
                                                  AbilityScoringProfileEntity profile) {
        JudgeAssessmentEntity judge = new JudgeAssessmentEntity();
        setId(judge, id);
        judge.setRequestId("judge-request");
        judge.setUserId("student-1");
        judge.setAbilityStateId(state.getId());
        judge.setScoreResultId(result.getId());
        judge.setStatus(JudgeAssessmentStatus.IN_PROGRESS);
        judge.setDecision(JudgeDecision.PENDING);
        judge.setTriggerReasonsJson("[\"RANK_PROMOTION_REQUIRES_JUDGE\"]");
        judge.setQuestionsJson(toJson(List.of(
                new JudgeDtos.Question("q1", "贡献？", "贡献", 10),
                new JudgeDtos.Question("q2", "原理？", "原理", 10),
                new JudgeDtos.Question("q3", "排错？", "排错", 10)
        )));
        judge.setAnswersJson("[]");
        judge.setRubricResultJson("{}");
        judge.setRubricVersion(JudgeAssessmentService.RUBRIC_VERSION);
        judge.setAbilityScoreAtTrigger(result.getNewAbilityScore());
        judge.setConfidenceBefore(profile.getProfileConfidence());
        judge.setProposedConfidenceDelta(BigDecimal.ZERO);
        return judge;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void setId(Object target, Long id) {
        try {
            var field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
