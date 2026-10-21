package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityScoringProfileEntity;
import com.donotmiss.backend.abilityscore.AbilityScoringProfileRepository;
import com.donotmiss.backend.abilityscore.JudgeDecision;
import com.donotmiss.backend.abilityscore.JudgeDecisionPolicy;
import com.donotmiss.backend.abilityscore.JudgeDtos;
import com.donotmiss.backend.abilityscore.JudgeRubricSupport;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unlocks a held V2 rank promotion through a short, evidence-grounded question round.
 *
 * <p>The LLM writes questions and grades answers; {@link JudgeRubricSupport} clamps what it
 * returns and {@link JudgeDecisionPolicy} turns the score into PASS / FAIL / MANUAL_REVIEW.
 * A pass only changes the rank (to the rank the current score already earns); it never adds
 * experience or ability score, so a review cannot become a way to farm points.</p>
 */
@Service
public class CapabilityV2PromotionReviewService {
    private static final int CONTEXT_EVIDENCE_LIMIT = 6;
    private static final BigDecimal DEFAULT_PROFILE_CONFIDENCE = new BigDecimal("0.5000");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Tokyo"));

    private final CapabilityV2BucketDetailService detailService;
    private final CapabilityPromotionReviewRepository reviewRepository;
    private final UserCapabilityStateRepository stateRepository;
    private final AbilityScoringProfileRepository profileRepository;
    private final RankPromotionPolicy rankPolicy;
    private final JudgeDecisionPolicy decisionPolicy;
    private final OpenAiCompatibleLlmClient llmClient;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public CapabilityV2PromotionReviewService(CapabilityV2BucketDetailService detailService,
                                              CapabilityPromotionReviewRepository reviewRepository,
                                              UserCapabilityStateRepository stateRepository,
                                              AbilityScoringProfileRepository profileRepository,
                                              RankPromotionPolicy rankPolicy,
                                              JudgeDecisionPolicy decisionPolicy,
                                              OpenAiCompatibleLlmClient llmClient,
                                              AgentRunService agentRunService,
                                              ObjectMapper objectMapper) {
        this.detailService = detailService;
        this.reviewRepository = reviewRepository;
        this.stateRepository = stateRepository;
        this.profileRepository = profileRepository;
        this.rankPolicy = rankPolicy;
        this.decisionPolicy = decisionPolicy;
        this.llmClient = llmClient;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    /**
     * Opens (or resumes) the review for a held promotion. Reviews are created lazily here rather
     * than on the scoring path, so legacy buckets that were held before this feature work the same.
     */
    @Transactional
    public CapabilityPromotionReviews.View start(String userId, Long bucketId) {
        // Also proves ownership: throws 404 unless the user has a state row for this bucket.
        CapabilityV2BucketDetailService.BucketDetail detail = detailService.detailForUser(userId, bucketId);
        CapabilityV2BucketDetailService.Promotion promotion = detail.promotion();
        if (promotion.openReview() != null) {
            return promotion.openReview();
        }
        if (!promotion.pending()) {
            throw ApiException.badRequest("这个标签当前没有等待复核的晋级");
        }
        if (!promotion.canStartReview()) {
            throw ApiException.badRequest("上次复核未通过，请补充新的经历，或在 "
                    + DATE.format(promotion.retryAvailableAt()) + " 之后再试");
        }

        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.ABILITY_JUDGE,
                "晋级复核出题：" + detail.displayName(),
                "bucketId=" + bucketId + ", " + promotion.currentRank() + "->" + promotion.targetRank()
        );
        try {
            Long stepId = agentRunService.startStep(runId, AgentStepName.JUDGE_QUESTION_GENERATION,
                    "Generate experience-first promotion questions for bucket " + bucketId);
            List<JudgeDtos.Question> questions = generateQuestions(detail);
            agentRunService.completeStep(stepId, "questions=" + questions.size() + ", mode=" + llmClient.modeLabel());

            UserCapabilityStateEntity state = ownedState(userId, bucketId);
            CapabilityPromotionReviewEntity review = new CapabilityPromotionReviewEntity();
            review.setUserId(userId);
            review.setBucketId(bucketId);
            review.setUserStateId(state.getId());
            review.setTriggerEvidenceLinkId(promotion.triggerEvidenceLinkId());
            review.setFromRank(promotion.currentRank());
            review.setTargetRank(promotion.targetRank());
            review.setAbilityScoreAtStart(state.getAbilityScore());
            review.setQuestionsJson(toJson(questions));
            review.setConfidenceBefore(profile(userId).getProfileConfidence());
            review.setOpenKey(CapabilityPromotionReviewEntity.openKeyFor(userId, bucketId));
            CapabilityPromotionReviewEntity saved = reviewRepository.save(review);
            agentRunService.finishRun(runId, "reviewId=" + saved.getId());
            return CapabilityPromotionReviews.view(saved, objectMapper);
        } catch (RuntimeException | Error ex) {
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    @Transactional
    public CapabilityPromotionReviews.View submit(String userId, Long reviewId, List<JudgeDtos.Answer> submitted) {
        CapabilityPromotionReviewEntity review = reviewRepository.findById(reviewId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("复核任务不存在"));
        if (review.getStatus() == CapabilityPromotionReviewEntity.Status.COMPLETED) {
            return CapabilityPromotionReviews.view(review, objectMapper);
        }

        List<JudgeDtos.Question> questions = readQuestions(review.getQuestionsJson());
        List<JudgeDtos.Answer> answers = JudgeRubricSupport.validateAnswers(questions, submitted);
        CapabilityV2BucketDetailService.BucketDetail detail = detailService.detailForUser(userId, review.getBucketId());

        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.ABILITY_JUDGE,
                "晋级复核判卷：" + detail.displayName(),
                "reviewId=" + reviewId + ", answerCount=" + answers.size()
        );
        try {
            Long evaluationStepId = agentRunService.startStep(runId, AgentStepName.JUDGE_EVALUATION,
                    "Grade answers against the bucket's own evidence.");
            Evaluation evaluation = evaluate(detail, questions, answers);
            agentRunService.completeStep(evaluationStepId, "modelEvaluated=" + evaluation.modelEvaluated()
                    + ", totalScore=" + evaluation.rubric().totalScore());

            Long applyStepId = agentRunService.startStep(runId, AgentStepName.JUDGE_APPLY,
                    "Apply rank unlock and bounded profile confidence delta.");
            JudgeDecisionPolicy.JudgeOutcome outcome = decisionPolicy.decide(
                    evaluation.rubric().totalScore(), evaluation.modelEvaluated());
            applyOutcome(review, outcome, evaluation, answers);
            CapabilityPromotionReviewEntity saved = reviewRepository.save(review);
            agentRunService.completeStep(applyStepId, "decision=" + saved.getDecision()
                    + ", appliedRank=" + saved.getAppliedRank());
            agentRunService.finishRun(runId, "reviewId=" + saved.getId() + ", decision=" + saved.getDecision());
            return CapabilityPromotionReviews.view(saved, objectMapper);
        } catch (RuntimeException | Error ex) {
            // The review stays IN_PROGRESS, so the user can resubmit after a transient failure.
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private void applyOutcome(CapabilityPromotionReviewEntity review,
                              JudgeDecisionPolicy.JudgeOutcome outcome,
                              Evaluation evaluation,
                              List<JudgeDtos.Answer> answers) {
        review.setAnswersJson(toJson(answers));
        review.setRubricJson(toJson(evaluation.rubric()));
        review.setModelEvaluated(evaluation.modelEvaluated());
        review.setDecision(outcome.decision());
        review.setStatus(CapabilityPromotionReviewEntity.Status.COMPLETED);
        review.setCompletedAt(Instant.now());
        review.setOpenKey(null);

        if (outcome.decision() == JudgeDecision.PASS) {
            UserCapabilityStateEntity state = ownedState(review.getUserId(), review.getBucketId());
            // Agreed rule: unlock straight to the rank the score earns now, which may be above the target at start.
            String earnedRank = rankPolicy.rankFor(state.getAbilityScore());
            if (rankPolicy.index(earnedRank) > rankPolicy.index(state.getRankName())) {
                state.setRankName(earnedRank);
                stateRepository.save(state);
            }
            review.setAppliedRank(state.getRankName());
        }
        if (outcome.decision() != JudgeDecision.MANUAL_REVIEW) {
            AbilityScoringProfileEntity profile = profile(review.getUserId());
            BigDecimal before = profile.getProfileConfidence() == null
                    ? DEFAULT_PROFILE_CONFIDENCE
                    : profile.getProfileConfidence();
            BigDecimal after = before.add(outcome.confidenceDelta())
                    .max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
            profile.setProfileConfidence(after);
            profileRepository.save(profile);
            review.setConfidenceDelta(after.subtract(before).setScale(4, RoundingMode.HALF_UP));
        }
    }

    private List<JudgeDtos.Question> generateQuestions(CapabilityV2BucketDetailService.BucketDetail detail) {
        boolean function = detail.bucketType() == CapabilityBucketType.FUNCTION;
        String systemPrompt = """
                你是 Do Not Miss 的能力晋级复核官。用户在某个能力标签上的分数已经达到更高阶段，
                你要出 3 道问题，确认这项能力确实属于用户本人。
                出题优先级必须严格遵守：
                q1、q2：围绕用户自己的经历。追问输入材料里具体的行动、决策、遇到的问题和取舍，
                        题干要点名具体经历（标题或事实点），让只有亲身做过的人才能答好。优先使用 triggerEvidence。
                q3：与该能力相关的延伸问题，可以不局限于这次经历，难度与 targetRank 匹配。
                %s
                不得询问材料之外的隐私，不得泄露参考答案，不得出纯背诵题。每题不超过 120 字。
                只返回 JSON：
                {"questions":[{"id":"q1","prompt":"问题","focus":"验证点","maxScore":10}]}
                """.formatted(function
                ? "这是“核心职能”标签：q3 侧重职责边界、交付质量、协作与结果衡量。"
                : "这是“知识与技术”标签：q3 侧重核心原理、方法选择、迁移应用或局限性。");
        return llmClient.chatForJson(systemPrompt, toJson(questionContext(detail)), JudgeDtos.QuestionModelResponse.class)
                .map(JudgeDtos.QuestionModelResponse::questions)
                .map(JudgeRubricSupport::sanitizeQuestions)
                .filter(items -> items.size() == JudgeRubricSupport.QUESTION_COUNT)
                .orElseGet(() -> fallbackQuestions(detail));
    }

    private Evaluation evaluate(CapabilityV2BucketDetailService.BucketDetail detail,
                                List<JudgeDtos.Question> questions,
                                List<JudgeDtos.Answer> answers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("context", questionContext(detail));
        payload.put("questions", questions);
        payload.put("answers", answers);
        String systemPrompt = """
                你是严格的能力晋级复核 Judge。请依据题目、回答和用户的经历证据逐题评分，不要因为回答较长就给高分。
                q1、q2 重点检查：回答是否与经历证据一致、是否包含本人实际操作和可核对的细节；与证据矛盾或明显编造时给低分。
                q3 重点检查：原理或方法是否正确、能否解释因果和边界。
                每题只能给 0 到该题 maxScore 的整数分。
                只返回 JSON：
                {"items":[{"questionId":"q1","score":0,"maxScore":10,"feedback":"判分理由","evidence":["回答中的依据"]}],
                 "overallFeedback":"总体评价"}
                """;
        return llmClient.chatForJson(systemPrompt, toJson(payload), JudgeDtos.EvaluationModelResponse.class)
                .map(response -> new Evaluation(JudgeRubricSupport.sanitizeRubric(questions, response), true))
                .orElseGet(() -> new Evaluation(new JudgeDtos.RubricResult(
                        0, 0, questions.size() * JudgeRubricSupport.QUESTION_MAX_SCORE,
                        "模型判卷暂不可用，本次不调整阶段，可以稍后重新发起复核。",
                        List.of()
                ), false));
    }

    /** Only the user's own evidence on this bucket, with the evidence that opened the hold first. */
    private Map<String, Object> questionContext(CapabilityV2BucketDetailService.BucketDetail detail) {
        Long triggerId = detail.promotion().triggerEvidenceLinkId();
        CapabilityV2BucketDetailService.TimelineItem trigger = detail.timeline().stream()
                .filter(item -> Objects.equals(item.evidenceLinkId(), triggerId))
                .findFirst()
                .orElse(null);
        List<Map<String, Object>> others = new ArrayList<>();
        for (CapabilityV2BucketDetailService.TimelineItem item : detail.timeline().reversed()) {
            if (item != trigger && others.size() < CONTEXT_EVIDENCE_LIMIT) {
                others.add(evidenceContext(item));
            }
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("bucketName", detail.displayName());
        context.put("bucketType", detail.bucketType() == CapabilityBucketType.FUNCTION ? "核心职能" : "知识与技术");
        context.put("bucketDescription", detail.description());
        context.put("currentRank", detail.promotion().currentRank());
        context.put("targetRank", detail.promotion().targetRank());
        context.put("abilityScore", detail.abilityScore());
        context.put("triggerEvidence", trigger == null ? null : evidenceContext(trigger));
        context.put("otherEvidence", others);
        return context;
    }

    private Map<String, Object> evidenceContext(CapabilityV2BucketDetailService.TimelineItem item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", item.title());
        data.put("date", item.occurredAt() == null ? null : DATE.format(item.occurredAt()));
        data.put("claimedOutcome", JudgeRubricSupport.compact(item.claimedOutcome(), 400));
        data.put("findings", item.evidenceFindings());
        data.put("did", JudgeRubricSupport.compact(item.did(), 600));
        data.put("learned", JudgeRubricSupport.compact(item.learned(), 600));
        return data;
    }

    private List<JudgeDtos.Question> fallbackQuestions(CapabilityV2BucketDetailService.BucketDetail detail) {
        String bucket = detail.displayName();
        String anchor = detail.timeline().stream()
                .filter(item -> Objects.equals(item.evidenceLinkId(), detail.promotion().triggerEvidenceLinkId()))
                .map(CapabilityV2BucketDetailService.TimelineItem::title)
                .filter(Objects::nonNull)
                .findFirst()
                .map(title -> "在“" + title + "”中，")
                .orElse("结合你最近的一段经历，");
        return List.of(
                new JudgeDtos.Question("q1", anchor + "你在“" + bucket + "”方面亲自完成了哪些工作？请给出可以核对的具体细节。",
                        "本人贡献与事实一致性", JudgeRubricSupport.QUESTION_MAX_SCORE),
                new JudgeDtos.Question("q2", anchor + "你遇到的最棘手的问题是什么？你考虑过哪些做法，最后为什么这样选？",
                        "问题定位与取舍", JudgeRubricSupport.QUESTION_MAX_SCORE),
                new JudgeDtos.Question("q3", "如果把“" + bucket + "”用到一个规模更大或条件不同的场景，你现在的做法哪里最可能出问题？",
                        "迁移应用与边界意识", JudgeRubricSupport.QUESTION_MAX_SCORE)
        );
    }

    private UserCapabilityStateEntity ownedState(String userId, Long bucketId) {
        return stateRepository.findByUserIdAndBucketId(userId, bucketId)
                .orElseThrow(() -> ApiException.notFound("能力标签不存在"));
    }

    private AbilityScoringProfileEntity profile(String userId) {
        return profileRepository.findById(userId).orElseGet(() -> {
            AbilityScoringProfileEntity created = new AbilityScoringProfileEntity();
            created.setUserId(userId);
            created.setProfileConfidence(DEFAULT_PROFILE_CONFIDENCE);
            return profileRepository.save(created);
        });
    }

    private List<JudgeDtos.Question> readQuestions(String json) {
        try {
            var type = objectMapper.getTypeFactory().constructCollectionType(List.class, JudgeDtos.Question.class);
            List<JudgeDtos.Question> questions = objectMapper.readValue(json == null ? "[]" : json, type);
            return questions == null ? List.of() : questions;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("晋级复核 JSON 序列化失败", exception);
        }
    }

    private record Evaluation(JudgeDtos.RubricResult rubric, boolean modelEvaluated) {
    }
}
