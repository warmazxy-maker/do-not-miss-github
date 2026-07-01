package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JudgeAssessmentService {
    public static final String RUBRIC_VERSION = "ability-judge-v1.0";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(4);

    private final JudgeAssessmentRepository judgeRepository;
    private final AbilityScoreResultRepository resultRepository;
    private final UserAbilityStateRepository stateRepository;
    private final AbilityScoringProfileRepository profileRepository;
    private final AbilityEvidenceDimensionRepository dimensionRepository;
    private final AchievementRecordRepository recordRepository;
    private final OpenAiCompatibleLlmClient llmClient;
    private final JudgeDecisionPolicy decisionPolicy;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public JudgeAssessmentService(JudgeAssessmentRepository judgeRepository,
                                  AbilityScoreResultRepository resultRepository,
                                  UserAbilityStateRepository stateRepository,
                                  AbilityScoringProfileRepository profileRepository,
                                  AbilityEvidenceDimensionRepository dimensionRepository,
                                  AchievementRecordRepository recordRepository,
                                  OpenAiCompatibleLlmClient llmClient,
                                  JudgeDecisionPolicy decisionPolicy,
                                  AgentRunService agentRunService,
                                  ObjectMapper objectMapper) {
        this.judgeRepository = judgeRepository;
        this.resultRepository = resultRepository;
        this.stateRepository = stateRepository;
        this.profileRepository = profileRepository;
        this.dimensionRepository = dimensionRepository;
        this.recordRepository = recordRepository;
        this.llmClient = llmClient;
        this.decisionPolicy = decisionPolicy;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JudgeAssessmentEntity createIfRequired(AbilityScoreResultEntity result, List<String> triggerReasons) {
        if (result == null
                || result.getId() == null
                || result.getStatus() != AbilityScoreResultStatus.REVIEW_REQUIRED
                || triggerReasons == null
                || triggerReasons.isEmpty()) {
            return null;
        }
        Optional<JudgeAssessmentEntity> existing = judgeRepository.findByScoreResultId(result.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        UserAbilityStateEntity state = stateRepository.findById(result.getAbilityStateId())
                .orElseThrow(() -> ApiException.notFound("能力状态不存在：" + result.getAbilityStateId()));
        AbilityScoringProfileEntity profile = profileRepository.findById(result.getUserId())
                .orElseGet(() -> createProfile(result.getUserId()));

        JudgeAssessmentEntity assessment = new JudgeAssessmentEntity();
        assessment.setRequestId(UUID.randomUUID().toString());
        assessment.setUserId(result.getUserId());
        assessment.setAbilityStateId(state.getId());
        assessment.setScoreResultId(result.getId());
        assessment.setStatus(JudgeAssessmentStatus.PENDING);
        assessment.setDecision(JudgeDecision.PENDING);
        assessment.setTriggerReasonsJson(toJson(triggerReasons.stream().distinct().toList()));
        assessment.setQuestionsJson("[]");
        assessment.setAnswersJson("[]");
        assessment.setRubricResultJson("{}");
        assessment.setRubricVersion(RUBRIC_VERSION);
        assessment.setJudgeModelName(llmClient.modeLabel());
        assessment.setAbilityScoreAtTrigger(result.getNewAbilityScore());
        assessment.setConfidenceBefore(profile.getProfileConfidence());
        assessment.setProposedConfidenceDelta(ZERO);
        return judgeRepository.save(assessment);
    }

    @Transactional(readOnly = true)
    public List<JudgeDtos.AssessmentResponse> list(String userId) {
        return judgeRepository.findTop30ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public JudgeDtos.AssessmentResponse get(String userId, Long judgeId) {
        return toResponse(owned(userId, judgeId));
    }

    @Transactional
    public JudgeDtos.AssessmentResponse start(String userId, Long judgeId) {
        JudgeAssessmentEntity assessment = owned(userId, judgeId);
        if (assessment.getStatus() == JudgeAssessmentStatus.COMPLETED) {
            return toResponse(assessment);
        }
        if (assessment.getStatus() == JudgeAssessmentStatus.IN_PROGRESS
                && !readQuestions(assessment.getQuestionsJson()).isEmpty()) {
            return toResponse(assessment);
        }

        AbilityScoreResultEntity result = scoreResult(assessment);
        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.ABILITY_JUDGE,
                "能力考核：" + result.getDimensionName(),
                "judgeId=" + assessment.getId() + ", scoreResultId=" + result.getId()
        );
        try {
            Long stepId = agentRunService.startStep(
                    runId,
                    AgentStepName.JUDGE_QUESTION_GENERATION,
                    "Generate evidence-grounded questions for " + result.getNormalizedDimension()
            );
            List<JudgeDtos.Question> questions = generateQuestions(result);
            assessment.setQuestionsJson(toJson(questions));
            assessment.setStatus(JudgeAssessmentStatus.IN_PROGRESS);
            assessment.setDecision(JudgeDecision.PENDING);
            assessment.setStartedAt(assessment.getStartedAt() == null ? Instant.now() : assessment.getStartedAt());
            assessment.setJudgeModelName(llmClient.modeLabel());
            JudgeAssessmentEntity saved = judgeRepository.save(assessment);
            agentRunService.completeStep(stepId, "questions=" + questions.size() + ", mode=" + llmClient.modeLabel());
            agentRunService.finishRun(runId, "judgeId=" + saved.getId() + ", status=IN_PROGRESS");
            return toResponse(saved);
        } catch (RuntimeException | Error ex) {
            assessment.setStatus(JudgeAssessmentStatus.FAILED);
            judgeRepository.save(assessment);
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    @Transactional
    public JudgeDtos.AssessmentResponse submit(String userId,
                                               Long judgeId,
                                               JudgeDtos.SubmitRequest request) {
        JudgeAssessmentEntity assessment = owned(userId, judgeId);
        if (assessment.getStatus() == JudgeAssessmentStatus.COMPLETED) {
            return toResponse(assessment);
        }
        if (assessment.getStatus() != JudgeAssessmentStatus.IN_PROGRESS) {
            throw ApiException.badRequest("请先开始能力考核，再提交答案");
        }

        List<JudgeDtos.Question> questions = readQuestions(assessment.getQuestionsJson());
        List<JudgeDtos.Answer> answers = validateAnswers(questions, request.answers());
        AbilityScoreResultEntity result = scoreResult(assessment);
        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.ABILITY_JUDGE,
                "能力考核判卷：" + result.getDimensionName(),
                "judgeId=" + assessment.getId() + ", answerCount=" + answers.size()
        );
        try {
            assessment.setAnswersJson(toJson(answers));
            Long evaluationStepId = agentRunService.startStep(
                    runId,
                    AgentStepName.JUDGE_EVALUATION,
                    "Evaluate answers against evidence-grounded rubric."
            );
            Evaluation evaluation = evaluate(result, questions, answers);
            agentRunService.completeStep(
                    evaluationStepId,
                    "modelEvaluated=" + evaluation.modelEvaluated()
                            + ", totalScore=" + evaluation.rubric().totalScore()
            );

            Long applyStepId = agentRunService.startStep(
                    runId,
                    AgentStepName.JUDGE_APPLY,
                    "Apply bounded confidence delta and rank confirmation."
            );
            JudgeDecisionPolicy.JudgeOutcome outcome = decisionPolicy.decide(
                    evaluation.rubric().totalScore(),
                    evaluation.modelEvaluated()
            );
            applyOutcome(assessment, result, outcome, evaluation.rubric());
            JudgeAssessmentEntity saved = judgeRepository.save(assessment);
            agentRunService.completeStep(
                    applyStepId,
                    "decision=" + saved.getDecision()
                            + ", confidenceDelta=" + saved.getProposedConfidenceDelta()
            );
            agentRunService.finishRun(runId, "judgeId=" + saved.getId() + ", decision=" + saved.getDecision());
            return toResponse(saved);
        } catch (RuntimeException | Error ex) {
            assessment.setStatus(JudgeAssessmentStatus.FAILED);
            judgeRepository.save(assessment);
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private List<JudgeDtos.Question> generateQuestions(AbilityScoreResultEntity result) {
        Map<String, Object> context = judgeContext(result);
        String systemPrompt = """
                你是 Do Not Miss 的能力验证考官。
                你的任务不是重新给活动打分，而是围绕候选人的真实经历，生成 3 道能够验证目标能力的问题。
                问题必须覆盖：
                1. 本人实际贡献和可验证细节。
                2. 目标能力的核心原理或方法。
                3. 故障定位、迁移应用或局限性分析。
                不得询问输入材料之外的隐私，不得泄露参考答案，不得生成纯背诵式宽泛问题。
                只返回 JSON：
                {"questions":[{"id":"q1","prompt":"问题","focus":"验证点","maxScore":10}]}
                """;
        return llmClient.chatForJson(
                        systemPrompt,
                        toJson(context),
                        JudgeDtos.QuestionModelResponse.class
                )
                .map(JudgeDtos.QuestionModelResponse::questions)
                .map(this::sanitizeQuestions)
                .filter(items -> items.size() == 3)
                .orElseGet(() -> fallbackQuestions(result));
    }

    private Evaluation evaluate(AbilityScoreResultEntity result,
                                List<JudgeDtos.Question> questions,
                                List<JudgeDtos.Answer> answers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("context", judgeContext(result));
        payload.put("questions", questions);
        payload.put("answers", answers);

        String systemPrompt = """
                你是严格的能力验证 Judge。
                请依据题目、回答和经历证据逐题评分，不要因为回答较长就给高分。
                重点检查：技术或领域事实是否正确、回答是否包含本人实际操作、能否解释因果和边界、是否与输入经历一致。
                如果回答空泛、回避问题、与证据矛盾或明显编造，应降低得分。
                每题只能给 0 到该题 maxScore 的整数分。
                只返回 JSON：
                {
                  "items":[
                    {"questionId":"q1","score":0,"maxScore":10,"feedback":"判分理由","evidence":["回答中的依据"]}
                  ],
                  "overallFeedback":"总体评价"
                }
                """;

        Optional<JudgeDtos.EvaluationModelResponse> model = llmClient.chatForJson(
                systemPrompt,
                toJson(payload),
                JudgeDtos.EvaluationModelResponse.class
        );
        if (model.isEmpty()) {
            return new Evaluation(
                    new JudgeDtos.RubricResult(
                            0,
                            0,
                            questions.stream().mapToInt(JudgeDtos.Question::maxScore).sum(),
                            "模型判卷不可用，任务已转入人工复核，不调整能力等级与画像可信度。",
                            List.of()
                    ),
                    false
            );
        }
        return new Evaluation(sanitizeRubric(questions, model.get()), true);
    }

    private void applyOutcome(JudgeAssessmentEntity assessment,
                              AbilityScoreResultEntity result,
                              JudgeDecisionPolicy.JudgeOutcome outcome,
                              JudgeDtos.RubricResult rubric) {
        AbilityScoringProfileEntity profile = profileRepository.findById(assessment.getUserId())
                .orElseGet(() -> createProfile(assessment.getUserId()));
        UserAbilityStateEntity state = stateRepository.findById(assessment.getAbilityStateId())
                .orElseThrow(() -> ApiException.notFound("能力状态不存在：" + assessment.getAbilityStateId()));

        BigDecimal before = profile.getProfileConfidence();
        BigDecimal delta = outcome.confidenceDelta().setScale(4, RoundingMode.HALF_UP);
        BigDecimal after = clamp(before.add(delta), ZERO, ONE);

        assessment.setDecision(outcome.decision());
        assessment.setStatus(JudgeAssessmentStatus.COMPLETED);
        assessment.setRubricResultJson(toJson(rubric));
        assessment.setConfidenceBefore(before);
        assessment.setProposedConfidenceDelta(after.subtract(before).setScale(4, RoundingMode.HALF_UP));
        assessment.setConfidenceAfter(after);
        assessment.setCompletedAt(Instant.now());
        assessment.setReviewerType(outcome.decision() == JudgeDecision.MANUAL_REVIEW ? "MANUAL_REQUIRED" : "LLM");
        assessment.setReviewerId(outcome.decision() == JudgeDecision.MANUAL_REVIEW ? null : llmClient.modeLabel());
        assessment.setReviewReason(rubric.overallFeedback());

        if (outcome.decision() == JudgeDecision.PASS) {
            profile.setProfileConfidence(after);
            state.setRankName(readProposedRank(result));
            result.setStatus(AbilityScoreResultStatus.VERIFIED);
            profileRepository.save(profile);
            stateRepository.save(state);
            resultRepository.save(result);
        } else if (outcome.decision() == JudgeDecision.FAIL) {
            profile.setProfileConfidence(after);
            result.setStatus(AbilityScoreResultStatus.PROVISIONAL);
            profileRepository.save(profile);
            resultRepository.save(result);
        }
    }

    private List<JudgeDtos.Answer> validateAnswers(List<JudgeDtos.Question> questions,
                                                   List<JudgeDtos.Answer> submitted) {
        if (questions.isEmpty()) {
            throw ApiException.badRequest("考核题目尚未生成");
        }
        Map<String, JudgeDtos.Answer> byId = submitted == null ? Map.of() : submitted.stream()
                .filter(answer -> answer != null && answer.questionId() != null)
                .collect(Collectors.toMap(
                        answer -> answer.questionId().trim(),
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        Set<String> expectedIds = questions.stream()
                .map(JudgeDtos.Question::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!expectedIds.equals(byId.keySet())) {
            throw ApiException.badRequest("必须完整回答当前考核的全部题目，且不能提交未知题目");
        }
        return questions.stream()
                .map(question -> {
                    JudgeDtos.Answer answer = byId.get(question.id());
                    String text = answer.answer() == null ? "" : answer.answer().trim();
                    if (text.length() < 8) {
                        throw ApiException.badRequest("答案过短：" + question.id());
                    }
                    return new JudgeDtos.Answer(question.id(), text);
                })
                .toList();
    }

    private JudgeDtos.RubricResult sanitizeRubric(List<JudgeDtos.Question> questions,
                                                   JudgeDtos.EvaluationModelResponse response) {
        Map<String, JudgeDtos.RubricItem> modelItems = response.items() == null ? Map.of() : response.items().stream()
                .filter(item -> item != null && item.questionId() != null)
                .collect(Collectors.toMap(
                        item -> item.questionId().trim(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<JudgeDtos.RubricItem> items = new ArrayList<>();
        int earned = 0;
        int maximum = 0;
        for (JudgeDtos.Question question : questions) {
            JudgeDtos.RubricItem raw = modelItems.get(question.id());
            int maxScore = Math.min(Math.max(question.maxScore(), 1), 20);
            int score = raw == null || raw.score() == null
                    ? 0
                    : Math.min(Math.max(raw.score(), 0), maxScore);
            earned += score;
            maximum += maxScore;
            items.add(new JudgeDtos.RubricItem(
                    question.id(),
                    score,
                    maxScore,
                    compact(raw == null ? "模型未返回该题判分" : raw.feedback(), 500),
                    raw == null || raw.evidence() == null
                            ? List.of()
                            : raw.evidence().stream()
                            .filter(value -> value != null && !value.isBlank())
                            .map(value -> compact(value, 240))
                            .limit(5)
                            .toList()
            ));
        }
        int total = maximum == 0 ? 0 : (int) Math.round(earned * 100.0 / maximum);
        return new JudgeDtos.RubricResult(
                total,
                earned,
                maximum,
                compact(response.overallFeedback(), 1000),
                List.copyOf(items)
        );
    }

    private List<JudgeDtos.Question> sanitizeQuestions(List<JudgeDtos.Question> values) {
        if (values == null) {
            return List.of();
        }
        List<JudgeDtos.Question> questions = new ArrayList<>();
        for (JudgeDtos.Question value : values) {
            if (value == null || value.prompt() == null || value.prompt().isBlank()) {
                continue;
            }
            String id = "q" + (questions.size() + 1);
            questions.add(new JudgeDtos.Question(
                    id,
                    compact(value.prompt(), 600),
                    compact(value.focus(), 180),
                    10
            ));
            if (questions.size() >= 3) {
                break;
            }
        }
        return List.copyOf(questions);
    }

    private List<JudgeDtos.Question> fallbackQuestions(AbilityScoreResultEntity result) {
        String dimension = result.getDimensionName();
        return List.of(
                new JudgeDtos.Question(
                        "q1",
                        "请结合这次经历，说明你在“" + dimension + "”方面亲自完成了哪些工作，并给出可以核对的具体细节。",
                        "个人贡献与事实一致性",
                        10
                ),
                new JudgeDtos.Question(
                        "q2",
                        "请解释“" + dimension + "”中一个你实际使用过的核心原理或方法，并说明为什么这样做。",
                        "核心知识与因果理解",
                        10
                ),
                new JudgeDtos.Question(
                        "q3",
                        "如果在类似任务中遇到失败或结果不符合预期，你会如何定位问题、验证假设并改进方案？",
                        "迁移能力、排错与边界意识",
                        10
                )
        );
    }

    private Map<String, Object> judgeContext(AbilityScoreResultEntity result) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("scoreResultId", result.getId());
        context.put("dimension", result.getDimensionName());
        context.put("normalizedDimension", result.getNormalizedDimension());
        context.put("triggerReasons", readStrings(result.getJudgeFlagsJson()));
        context.put("abilityScoreBefore", result.getOldAbilityScore());
        context.put("abilityScoreAfter", result.getNewAbilityScore());
        context.put("factorSnapshot", readObject(result.getFactorSnapshotJson()));

        recordRepository.findById(result.getAchievementRecordId()).ifPresent(record -> {
            context.put("achievement", achievementContext(record));
        });
        dimensionRepository.findByAssessmentIdOrderByRelevanceDesc(result.getAssessmentId()).stream()
                .filter(item -> result.getNormalizedDimension().equals(item.getNormalizedDimension()))
                .findFirst()
                .ifPresent(item -> context.put("evidenceDimension", Map.of(
                        "claimedOutcome", nullToEmpty(item.getClaimedOutcome()),
                        "relevance", item.getRelevance(),
                        "relevanceConfidence", item.getRelevanceConfidence(),
                        "evidenceRefs", readStrings(item.getEvidenceRefsJson())
                )));
        return context;
    }

    private Map<String, Object> achievementContext(AchievementRecordEntity record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", record.getEventTitle());
        data.put("organizationName", record.getOrganizationName());
        data.put("category", record.getCategory());
        data.put("content", record.getContent());
        data.put("skill", record.getSkill());
        data.put("did", record.getDid());
        data.put("learned", record.getLearned());
        return data;
    }

    private JudgeDtos.AssessmentResponse toResponse(JudgeAssessmentEntity entity) {
        AbilityScoreResultEntity result = scoreResult(entity);
        UserAbilityStateEntity state = stateRepository.findById(entity.getAbilityStateId())
                .orElseThrow(() -> ApiException.notFound("能力状态不存在：" + entity.getAbilityStateId()));
        return new JudgeDtos.AssessmentResponse(
                entity.getId(),
                entity.getRequestId(),
                entity.getStatus().name(),
                entity.getDecision().name(),
                entity.getScoreResultId(),
                entity.getAbilityStateId(),
                result.getDimensionName(),
                result.getNormalizedDimension(),
                readStrings(entity.getTriggerReasonsJson()),
                readQuestions(entity.getQuestionsJson()),
                readAnswers(entity.getAnswersJson()),
                readRubric(entity.getRubricResultJson()),
                entity.getAbilityScoreAtTrigger(),
                entity.getConfidenceBefore(),
                entity.getProposedConfidenceDelta(),
                entity.getConfidenceAfter(),
                state.getRankName(),
                readProposedRank(result),
                entity.getReviewReason(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private JudgeAssessmentEntity owned(String userId, Long judgeId) {
        JudgeAssessmentEntity assessment = judgeRepository.findById(judgeId)
                .orElseThrow(() -> ApiException.notFound("Judge 任务不存在：" + judgeId));
        if (!assessment.getUserId().equals(userId)) {
            throw ApiException.forbidden("不能访问其他用户的 Judge 任务");
        }
        return assessment;
    }

    private AbilityScoreResultEntity scoreResult(JudgeAssessmentEntity assessment) {
        return resultRepository.findById(assessment.getScoreResultId())
                .orElseThrow(() -> ApiException.notFound("评分结果不存在：" + assessment.getScoreResultId()));
    }

    private AbilityScoringProfileEntity createProfile(String userId) {
        AbilityScoringProfileEntity profile = new AbilityScoringProfileEntity();
        profile.setUserId(userId);
        profile.setProfileConfidence(new BigDecimal("0.5000"));
        return profileRepository.save(profile);
    }

    private String readProposedRank(AbilityScoreResultEntity result) {
        Object value = readObject(result.getFactorSnapshotJson()).get("proposedRank");
        return value == null ? "UNRATED" : value.toString();
    }

    private List<String> readStrings(String json) {
        try {
            var type = objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);
            return objectMapper.readValue(json == null ? "[]" : json, type);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private List<JudgeDtos.Question> readQuestions(String json) {
        return readList(json, JudgeDtos.Question.class);
    }

    private List<JudgeDtos.Answer> readAnswers(String json) {
        return readList(json, JudgeDtos.Answer.class);
    }

    private JudgeDtos.RubricResult readRubric(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JudgeDtos.RubricResult.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private <T> List<T> readList(String json, Class<T> elementType) {
        try {
            var type = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return objectMapper.readValue(json == null ? "[]" : json, type);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, LinkedHashMap.class);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Judge 审计 JSON 序列化失败", ex);
        }
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.max(min).min(max).setScale(4, RoundingMode.HALF_UP);
    }

    private String compact(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Evaluation(
            JudgeDtos.RubricResult rubric,
            boolean modelEvaluated
    ) {
    }
}
