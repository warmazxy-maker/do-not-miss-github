package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.abilityscore.EvidenceAssessmentDtos;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResumeAnswerEvidenceEvaluatorService {
    public static final String PROMPT_VERSION = "resume-answer-evidence-v1.0";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ResumeInterviewSessionRepository sessionRepository;
    private final ResumeSnapshotRepository snapshotRepository;
    private final ResumeInterviewQuestionRepository questionRepository;
    private final ResumeInterviewAnswerRepository answerRepository;
    private final ResumeInterviewEvidenceRepository evidenceRepository;
    private final ResumeInterviewStateService stateService;
    private final ResumeInterviewService resumeInterviewService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final AgentRunService agentRunService;
    private final AgentTraceArtifactService traceArtifactService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ResumeAnswerEvidenceEvaluatorService(ResumeInterviewSessionRepository sessionRepository,
                                                ResumeSnapshotRepository snapshotRepository,
                                                ResumeInterviewQuestionRepository questionRepository,
                                                ResumeInterviewAnswerRepository answerRepository,
                                                ResumeInterviewEvidenceRepository evidenceRepository,
                                                ResumeInterviewStateService stateService,
                                                ResumeInterviewService resumeInterviewService,
                                                OpenAiCompatibleLlmClient llmClient,
                                                AgentRunService agentRunService,
                                                AgentTraceArtifactService traceArtifactService,
                                                ObjectMapper objectMapper,
                                                TransactionTemplate transactionTemplate) {
        this.sessionRepository = sessionRepository;
        this.snapshotRepository = snapshotRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.evidenceRepository = evidenceRepository;
        this.stateService = stateService;
        this.resumeInterviewService = resumeInterviewService;
        this.llmClient = llmClient;
        this.agentRunService = agentRunService;
        this.traceArtifactService = traceArtifactService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ResumeInterviewDtos.ResumeAssessmentDetailResponse answerAndEvaluate(
            String userId,
            Long sessionId,
            Long questionId,
            ResumeInterviewDtos.SubmitInterviewAnswerRequest request
    ) {
        String normalizedUserId = normalizeUserId(userId);
        ResumeInterviewSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, normalizedUserId)
                .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在"));
        if (session.getStatus() != ResumeInterviewSessionStatus.INTERVIEWING
                && session.getStatus() != ResumeInterviewSessionStatus.EVIDENCE_READY) {
            throw ApiException.badRequest("当前状态不能回答面试问题：" + session.getStatus());
        }

        ResumeInterviewQuestionEntity question = questionRepository.findByIdAndSessionId(questionId, session.getId())
                .orElseThrow(() -> ApiException.notFound("面试问题不存在"));
        if (question.getStatus() == ResumeInterviewQuestionStatus.SKIPPED) {
            throw ApiException.badRequest("该问题已跳过，不能提交回答");
        }

        String answerText = cleanAnswer(request.answerText());
        if (answerText.length() < 8) {
            throw ApiException.badRequest("回答太短，请至少写出一两句具体经历");
        }
        String answerHash = sha256(answerText);
        ResumeInterviewAnswerEntity answer = persistAnswer(session, question, answerText, answerHash);

        if (evidenceRepository.findByQuestionIdAndAnswerId(question.getId(), answer.getId()).isPresent()) {
            return resumeInterviewService.getDetail(normalizedUserId, sessionId);
        }

        Long runId = agentRunService.startRun(
                normalizedUserId,
                AgentRunType.RESUME_ANSWER_EVALUATOR,
                "评估面试回答证据：" + question.getTargetDimension(),
                "sessionId=" + session.getId() + ", questionId=" + question.getId() + ", answerId=" + answer.getId()
        );
        Long snapshotStep = null;
        Long evaluationStep = null;
        Long validationStep = null;
        try {
            snapshotStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_ANSWER_SNAPSHOT,
                    "Build answer evidence snapshot from parsed resume, question and answer."
            );
            ResumeSnapshotEntity snapshot = activeSnapshot(session);
            ResumeInterviewDtos.ParsedResume parsed = readParsedResume(snapshot);
            Map<String, Object> evidenceSnapshot = buildEvidenceSnapshot(
                    session,
                    snapshot,
                    parsed,
                    question,
                    answer
            );
            String evidenceHash = sha256(toJson(evidenceSnapshot));
            agentRunService.completeStep(
                    snapshotStep,
                    "snapshotId=" + snapshot.getId()
                            + ", questionId=" + question.getId()
                            + ", answerHash=" + answer.getAnswerHash()
                            + ", evidenceHash=" + evidenceHash
            );
            snapshotStep = null;

            evaluationStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_ANSWER_EVALUATION,
                    "Evaluate answer into unified EvidenceAssessmentDtos.ModelResponse. No final score."
            );
            AssessmentOutput output = evaluate(question, answer, evidenceSnapshot);
            agentRunService.completeStep(evaluationStep, "mode=" + output.mode());
            evaluationStep = null;

            validationStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_EVIDENCE_VALIDATION,
                    "Clamp values, normalize dimensions and persist unified evidence candidate."
            );
            EvidenceAssessmentDtos.ModelResponse sanitized = sanitize(output.response(), question, answer);
            ResumeInterviewDtos.UnifiedAbilityEvidence unified = new ResumeInterviewDtos.UnifiedAbilityEvidence(
                    ResumeInterviewDtos.EVIDENCE_SCHEMA_VERSION,
                    AbilityEvidenceSourceType.RESUME_INTERVIEW,
                    ResumeInterviewEvidenceKind.RESUME_AND_INTERVIEW,
                    session.getId(),
                    snapshot.getId(),
                    question.getId(),
                    answer.getId(),
                    evidenceHash,
                    sanitized
            );
            persistEvidenceAndStatus(
                    session.getId(),
                    question.getId(),
                    answer.getId(),
                    snapshot.getId(),
                    evidenceHash,
                    unified,
                    runId
            );
            traceArtifactService.record(
                    runId,
                    AgentStepName.RESUME_EVIDENCE_VALIDATION,
                    "RESUME_ANSWER_EVIDENCE",
                    Map.of(
                            "sessionId", session.getId(),
                            "snapshotId", snapshot.getId(),
                            "questionId", question.getId(),
                            "answerId", answer.getId(),
                            "answerHash", answer.getAnswerHash(),
                            "answerPreview", preview(answer.getAnswerText()),
                            "evidenceHash", evidenceHash,
                            "promptVersion", PROMPT_VERSION,
                            "model", output.mode(),
                            "assessment", sanitized
                    ),
                    "dimensions=" + size(sanitized.dimensions())
                            + ", confidence=" + sanitized.assessmentConfidence()
                            + ", risks=" + size(sanitized.riskFlags())
            );
            agentRunService.completeStep(
                    validationStep,
                    "dimensions=" + size(sanitized.dimensions())
                            + ", confidence=" + sanitized.assessmentConfidence()
            );
            validationStep = null;
            agentRunService.finishRun(runId, "evidenceReady=true, questionId=" + question.getId());
            return resumeInterviewService.getDetail(normalizedUserId, sessionId);
        } catch (RuntimeException | Error ex) {
            if (snapshotStep != null) {
                agentRunService.failStep(snapshotStep, ex);
            }
            if (evaluationStep != null) {
                agentRunService.failStep(evaluationStep, ex);
            }
            if (validationStep != null) {
                agentRunService.failStep(validationStep, ex);
            }
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private ResumeInterviewAnswerEntity persistAnswer(ResumeInterviewSessionEntity session,
                                                      ResumeInterviewQuestionEntity question,
                                                      String answerText,
                                                      String answerHash) {
        return transactionTemplate.execute(status -> answerRepository.findByQuestionId(question.getId())
                .map(existing -> {
                    if (!answerHash.equals(existing.getAnswerHash())) {
                        throw ApiException.badRequest("该问题已经回答过，当前版本暂不支持覆盖回答");
                    }
                    markQuestionAnswered(question.getId());
                    return existing;
                })
                .orElseGet(() -> {
                    ResumeInterviewAnswerEntity answer = new ResumeInterviewAnswerEntity();
                    answer.setSessionId(session.getId());
                    answer.setQuestionId(question.getId());
                    answer.setAnswerText(answerText);
                    answer.setAnswerHash(answerHash);
                    ResumeInterviewAnswerEntity saved = answerRepository.save(answer);
                    markQuestionAnswered(question.getId());
                    return saved;
                }));
    }

    private void markQuestionAnswered(Long questionId) {
        ResumeInterviewQuestionEntity question = questionRepository.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("面试问题不存在"));
        if (question.getStatus() == ResumeInterviewQuestionStatus.ASKED) {
            question.setStatus(ResumeInterviewQuestionStatus.ANSWERED);
            question.setAnsweredAt(Instant.now());
            questionRepository.save(question);
        }
    }

    private void persistEvidenceAndStatus(Long sessionId,
                                          Long questionId,
                                          Long answerId,
                                          Long snapshotId,
                                          String evidenceHash,
                                          ResumeInterviewDtos.UnifiedAbilityEvidence unified,
                                          Long agentTraceRunId) {
        transactionTemplate.execute(status -> {
            if (evidenceRepository.findByQuestionIdAndAnswerId(questionId, answerId).isPresent()) {
                return null;
            }
            ResumeInterviewEvidenceEntity evidence = new ResumeInterviewEvidenceEntity();
            evidence.setSessionId(sessionId);
            evidence.setResumeSnapshotId(snapshotId);
            evidence.setQuestionId(questionId);
            evidence.setAnswerId(answerId);
            evidence.setEvidenceKind(ResumeInterviewEvidenceKind.RESUME_AND_INTERVIEW);
            evidence.setSourceType(AbilityEvidenceSourceType.RESUME_INTERVIEW);
            evidence.setStatus(ResumeInterviewEvidenceStatus.EVIDENCE_READY);
            evidence.setEvidenceHash(evidenceHash);
            evidence.setAbilityEvidenceJson(toJson(unified));
            evidence.setAgentTraceRunId(agentTraceRunId);
            evidenceRepository.save(evidence);

            ResumeInterviewQuestionEntity question = questionRepository.findById(questionId)
                    .orElseThrow(() -> ApiException.notFound("面试问题不存在"));
            question.setStatus(ResumeInterviewQuestionStatus.EVALUATED);
            if (question.getAnsweredAt() == null) {
                question.setAnsweredAt(Instant.now());
            }
            questionRepository.save(question);

            ResumeInterviewSessionEntity session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在"));
            if (session.getStatus() == ResumeInterviewSessionStatus.INTERVIEWING && allQuestionsEvaluated(sessionId)) {
                stateService.moveTo(session, ResumeInterviewSessionStatus.EVIDENCE_READY);
                sessionRepository.save(session);
            }
            return null;
        });
    }

    private boolean allQuestionsEvaluated(Long sessionId) {
        List<ResumeInterviewQuestionEntity> questions = questionRepository.findBySessionIdOrderBySequenceNoAsc(sessionId);
        return !questions.isEmpty() && questions.stream()
                .allMatch(question -> question.getStatus() == ResumeInterviewQuestionStatus.EVALUATED
                        || question.getStatus() == ResumeInterviewQuestionStatus.SKIPPED);
    }

    private AssessmentOutput evaluate(ResumeInterviewQuestionEntity question,
                                      ResumeInterviewAnswerEntity answer,
                                      Map<String, Object> evidenceSnapshot) {
        if (llmClient.isEnabled()) {
            return llmClient.chatForJson(
                            systemPrompt(),
                            toJson(evidenceSnapshot),
                            EvidenceAssessmentDtos.ModelResponse.class
                    )
                    .map(response -> new AssessmentOutput(response, llmClient.modeLabel()))
                    .orElseGet(() -> new AssessmentOutput(localFallback(question, answer), "rule-fallback"));
        }
        return new AssessmentOutput(localFallback(question, answer), "rule");
    }

    private EvidenceAssessmentDtos.ModelResponse sanitize(EvidenceAssessmentDtos.ModelResponse raw,
                                                          ResumeInterviewQuestionEntity question,
                                                          ResumeInterviewAnswerEntity answer) {
        EvidenceAssessmentDtos.ModelResponse fallback = localFallback(question, answer);
        EvidenceAssessmentDtos.ModelResponse source = raw == null ? fallback : raw;
        List<EvidenceAssessmentDtos.DimensionItem> dimensions = sanitizeDimensions(
                source.dimensions(),
                question,
                fallback.dimensions()
        );
        List<EvidenceAssessmentDtos.EvidenceFinding> findings = sanitizeFindings(source.evidenceFindings(), question, answer);
        List<String> risks = strings(source.riskFlags(), 12, 120);
        if (answer.getAnswerText().length() < 80 && !risks.contains("ANSWER_TOO_SHORT")) {
            risks = append(risks, "ANSWER_TOO_SHORT", 12);
        }
        if (dimensions.isEmpty()) {
            dimensions = fallback.dimensions();
            risks = append(risks, "DIMENSION_FALLBACK_USED", 12);
        }

        return new EvidenceAssessmentDtos.ModelResponse(
                firstPresent(source.normalizedActivityType(), "RESUME_INTERVIEW_ANSWER"),
                clamp(source.activityDifficulty(), ZERO, HUNDRED, fallback.activityDifficulty()),
                clamp01(source.activityDifficultyConfidence(), fallback.activityDifficultyConfidence()),
                clamp01(source.completionQuality(), fallback.completionQuality()),
                clamp01(source.completionQualityConfidence(), fallback.completionQualityConfidence()),
                clamp01(source.personalContribution(), fallback.personalContribution()),
                clamp01(source.personalContributionConfidence(), fallback.personalContributionConfidence()),
                clamp01(source.assessmentConfidence(), fallback.assessmentConfidence()),
                dimensions,
                findings,
                sanitizeNovelty(source.noveltyFeatures(), question),
                risks,
                sanitizeJudgeRecommendation(source.judgeRecommendation(), risks)
        );
    }

    private List<EvidenceAssessmentDtos.DimensionItem> sanitizeDimensions(
            List<EvidenceAssessmentDtos.DimensionItem> rawDimensions,
            ResumeInterviewQuestionEntity question,
            List<EvidenceAssessmentDtos.DimensionItem> fallback
    ) {
        if (rawDimensions == null || rawDimensions.isEmpty()) {
            return fallback;
        }
        List<EvidenceAssessmentDtos.DimensionItem> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (EvidenceAssessmentDtos.DimensionItem item : rawDimensions) {
            if (item == null) {
                continue;
            }
            String dimension = compact(firstPresent(item.dimension(), question.getTargetDimension()), 100);
            String normalized = compact(firstPresent(item.normalizedDimension(), normalizeDimension(dimension)), 100);
            if (!seen.add(normalized)) {
                continue;
            }
            result.add(new EvidenceAssessmentDtos.DimensionItem(
                    dimension,
                    normalized,
                    clamp01(item.relevance(), new BigDecimal("0.75")),
                    clamp01(item.confidence(), new BigDecimal("0.50")),
                    compact(item.claimedOutcome(), 600),
                    strings(item.evidenceRefs(), 10, 120)
            ));
            if (result.size() >= 4) {
                break;
            }
        }
        return result;
    }

    private List<EvidenceAssessmentDtos.EvidenceFinding> sanitizeFindings(
            List<EvidenceAssessmentDtos.EvidenceFinding> rawFindings,
            ResumeInterviewQuestionEntity question,
            ResumeInterviewAnswerEntity answer
    ) {
        if (rawFindings == null || rawFindings.isEmpty()) {
            return List.of(defaultFinding(question, answer, new BigDecimal("0.45"), "SELF_REPORTED"));
        }
        List<EvidenceAssessmentDtos.EvidenceFinding> result = new ArrayList<>();
        for (EvidenceAssessmentDtos.EvidenceFinding finding : rawFindings) {
            if (finding == null) {
                continue;
            }
            result.add(new EvidenceAssessmentDtos.EvidenceFinding(
                    compact(firstPresent(finding.evidenceId(), "answer-" + answer.getId()), 80),
                    compact(firstPresent(finding.evidenceType(), "INTERVIEW_ANSWER"), 80),
                    clamp01(finding.quality(), new BigDecimal("0.45")),
                    compact(firstPresent(finding.verificationStatus(), "SELF_REPORTED"), 80),
                    strings(finding.supports(), 10, 160)
            ));
            if (result.size() >= 6) {
                break;
            }
        }
        return result.isEmpty() ? List.of(defaultFinding(question, answer, new BigDecimal("0.45"), "SELF_REPORTED")) : result;
    }

    private EvidenceAssessmentDtos.NoveltyFeatures sanitizeNovelty(EvidenceAssessmentDtos.NoveltyFeatures novelty,
                                                                   ResumeInterviewQuestionEntity question) {
        if (novelty == null) {
            return new EvidenceAssessmentDtos.NoveltyFeatures(
                    normalizeDimension(question.getTargetDimension()),
                    List.of(question.getTargetDimension()),
                    "INTERVIEW_EVIDENCE"
            );
        }
        return new EvidenceAssessmentDtos.NoveltyFeatures(
                compact(firstPresent(novelty.activityFamily(), normalizeDimension(question.getTargetDimension())), 100),
                strings(novelty.techniques(), 12, 80),
                compact(firstPresent(novelty.difficultyBand(), "INTERVIEW_EVIDENCE"), 80)
        );
    }

    private EvidenceAssessmentDtos.JudgeRecommendation sanitizeJudgeRecommendation(
            EvidenceAssessmentDtos.JudgeRecommendation recommendation,
            List<String> risks
    ) {
        boolean suggestReview = recommendation != null && Boolean.TRUE.equals(recommendation.suggestReview());
        if (risks.contains("ANSWER_TOO_SHORT") || risks.contains("GENERIC_ANSWER")) {
            suggestReview = true;
        }
        List<String> reasons = recommendation == null
                ? List.of()
                : strings(recommendation.reasons(), 8, 160);
        if (suggestReview && reasons.isEmpty()) {
            reasons = List.of("回答证据不足或存在泛化风险，建议后续 Judge 验证。");
        }
        return new EvidenceAssessmentDtos.JudgeRecommendation(suggestReview, reasons);
    }

    private EvidenceAssessmentDtos.ModelResponse localFallback(ResumeInterviewQuestionEntity question,
                                                               ResumeInterviewAnswerEntity answer) {
        String answerText = answer.getAnswerText();
        double lengthFactor = Math.min(answerText.length() / 900.0, 1.0);
        double concreteFactor = concreteSignal(answerText);
        BigDecimal completionQuality = bd(0.28 + lengthFactor * 0.35 + concreteFactor * 0.22);
        BigDecimal assessmentConfidence = bd(0.30 + lengthFactor * 0.22 + concreteFactor * 0.20);
        BigDecimal contribution = bd(answerText.contains("我") || answerText.toLowerCase(Locale.ROOT).contains("i ")
                ? 0.62
                : 0.45);
        List<String> risks = new ArrayList<>();
        risks.add("RULE_FALLBACK");
        if (answerText.length() < 80) {
            risks.add("ANSWER_TOO_SHORT");
        }
        if (concreteFactor < 0.20) {
            risks.add("GENERIC_ANSWER");
        }
        return new EvidenceAssessmentDtos.ModelResponse(
                "RESUME_INTERVIEW_ANSWER",
                new BigDecimal("55.0"),
                new BigDecimal("0.45"),
                completionQuality,
                new BigDecimal("0.45"),
                contribution,
                new BigDecimal("0.45"),
                assessmentConfidence,
                List.of(new EvidenceAssessmentDtos.DimensionItem(
                        question.getTargetDimension(),
                        question.getNormalizedDimension(),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.50"),
                        compact(answerText, 300),
                        readEvidenceRefs(question)
                )),
                List.of(defaultFinding(question, answer, completionQuality, "SELF_REPORTED")),
                new EvidenceAssessmentDtos.NoveltyFeatures(
                        question.getNormalizedDimension(),
                        List.of(question.getTargetDimension()),
                        "INTERVIEW_EVIDENCE"
                ),
                risks,
                new EvidenceAssessmentDtos.JudgeRecommendation(
                        risks.contains("ANSWER_TOO_SHORT") || risks.contains("GENERIC_ANSWER"),
                        risks.contains("ANSWER_TOO_SHORT") || risks.contains("GENERIC_ANSWER")
                                ? List.of("本地规则判断回答证据不足，建议后续 Judge 验证。")
                                : List.of()
                )
        );
    }

    private EvidenceAssessmentDtos.EvidenceFinding defaultFinding(ResumeInterviewQuestionEntity question,
                                                                  ResumeInterviewAnswerEntity answer,
                                                                  BigDecimal quality,
                                                                  String status) {
        return new EvidenceAssessmentDtos.EvidenceFinding(
                "answer-" + answer.getId(),
                "INTERVIEW_ANSWER",
                clamp01(quality, new BigDecimal("0.45")),
                status,
                List.of(
                        "question:" + question.getId(),
                        "answerHash:" + answer.getAnswerHash()
                )
        );
    }

    private Map<String, Object> buildEvidenceSnapshot(ResumeInterviewSessionEntity session,
                                                      ResumeSnapshotEntity snapshot,
                                                      ResumeInterviewDtos.ParsedResume parsed,
                                                      ResumeInterviewQuestionEntity question,
                                                      ResumeInterviewAnswerEntity answer) {
        List<String> refs = readEvidenceRefs(question);
        return new LinkedHashMap<>(Map.of(
                "sourceType", AbilityEvidenceSourceType.RESUME_INTERVIEW.name(),
                "promptVersion", PROMPT_VERSION,
                "sessionId", session.getId(),
                "targetRole", safe(session.getTargetRole()),
                "resumeSnapshot", Map.of(
                        "id", snapshot.getId(),
                        "resumeHash", snapshot.getResumeHash(),
                        "parserPromptVersion", safe(snapshot.getParserPromptVersion()),
                        "parserModelName", safe(snapshot.getParserModelName())
                ),
                "question", Map.of(
                        "id", question.getId(),
                        "targetDimension", safe(question.getTargetDimension()),
                        "normalizedDimension", safe(question.getNormalizedDimension()),
                        "questionText", safe(question.getQuestionText()),
                        "evidenceRefs", refs
                ),
                "resumeEvidenceSnippets", matchingSections(parsed, refs),
                "answer", Map.of(
                        "id", answer.getId(),
                        "answerText", safe(answer.getAnswerText()),
                        "answerHash", safe(answer.getAnswerHash())
                )
        ));
    }

    private List<Map<String, Object>> matchingSections(ResumeInterviewDtos.ParsedResume parsed, List<String> refs) {
        List<Map<String, Object>> sections = new ArrayList<>();
        collectMatchingSections(sections, "education", parsed.education(), refs);
        collectMatchingSections(sections, "projects", parsed.projects(), refs);
        collectMatchingSections(sections, "internships", parsed.internships(), refs);
        collectMatchingSections(sections, "contests", parsed.contests(), refs);
        collectMatchingSections(sections, "certificates", parsed.certificates(), refs);
        if (sections.isEmpty()) {
            collectAnySections(sections, "projects", parsed.projects());
            collectAnySections(sections, "internships", parsed.internships());
            collectAnySections(sections, "contests", parsed.contests());
        }
        return sections.stream().limit(6).toList();
    }

    private void collectMatchingSections(List<Map<String, Object>> result,
                                         String type,
                                         List<ResumeInterviewDtos.ResumeSection> sections,
                                         List<String> refs) {
        if (sections == null || sections.isEmpty() || refs == null || refs.isEmpty()) {
            return;
        }
        for (ResumeInterviewDtos.ResumeSection section : sections) {
            if (section == null) {
                continue;
            }
            if (refs.contains(section.id()) || refs.contains(section.title())) {
                result.add(sectionMap(type, section));
            }
        }
    }

    private void collectAnySections(List<Map<String, Object>> result,
                                    String type,
                                    List<ResumeInterviewDtos.ResumeSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return;
        }
        for (ResumeInterviewDtos.ResumeSection section : sections) {
            if (section != null) {
                result.add(sectionMap(type, section));
            }
            if (result.size() >= 3) {
                return;
            }
        }
    }

    private Map<String, Object> sectionMap(String type, ResumeInterviewDtos.ResumeSection section) {
        return Map.of(
                "type", type,
                "id", safe(section.id()),
                "title", safe(section.title()),
                "organization", safe(section.organization()),
                "timeRange", safe(section.timeRange()),
                "description", safe(section.description()),
                "evidenceRefs", section.evidenceRefs() == null ? List.of() : section.evidenceRefs()
        );
    }

    private ResumeSnapshotEntity activeSnapshot(ResumeInterviewSessionEntity session) {
        Long snapshotId = session.getActiveResumeSnapshotId();
        if (snapshotId == null) {
            throw ApiException.badRequest("当前会话没有可用于评估的简历快照");
        }
        return snapshotRepository.findByIdAndSessionId(snapshotId, session.getId())
                .orElseThrow(() -> ApiException.notFound("简历快照不存在"));
    }

    private ResumeInterviewDtos.ParsedResume readParsedResume(ResumeSnapshotEntity snapshot) {
        String json = snapshot.getParsedResumeJson();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            throw ApiException.badRequest("简历尚未结构化，请先执行 Resume Parser Agent");
        }
        try {
            return objectMapper.readValue(json, ResumeInterviewDtos.ParsedResume.class);
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("简历结构化结果无法读取，请重新解析简历");
        }
    }

    private List<String> readEvidenceRefs(ResumeInterviewQuestionEntity question) {
        String json = question.getEvidenceRefsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String systemPrompt() {
        return """
                你是 do not miss 的统一 Evidence Evaluator，当前场景是“简历面试回答评估”。
                你的职责：把“简历片段 + 面试问题 + 用户回答”转换成统一能力证据。
                你不能直接给最终能力分，不能说用户最终几分，不能修改用户画像。
                你只输出评分引擎需要的证据原料。

                字段含义：
                - normalizedActivityType：固定倾向 RESUME_INTERVIEW_ANSWER。
                - activityDifficulty：这段经历/问题所代表的能力难度，0-100，不是用户最终分。
                - completionQuality：回答是否具体、可验证、有工程细节，0-1。
                - personalContribution：回答中用户本人贡献是否清楚，0-1。
                - assessmentConfidence：你对本次证据判断的置信度，0-1。
                - dimensions：本次回答证明的能力维度，最多 4 个，每个要有 relevance。
                - evidenceFindings：证据点，说明哪些回答片段支持哪些能力。
                - riskFlags：风险标记，例如 ANSWER_TOO_SHORT、GENERIC_ANSWER、INCONSISTENT_WITH_RESUME、OVERCLAIMED、NO_CONCRETE_DETAIL。
                - judgeRecommendation：如果高影响或低置信，应建议后续 Judge 验证。

                判断原则：
                1. 简历片段只作为上下文，不要自动相信用户回答。
                2. 回答越具体，completionQuality 和 assessmentConfidence 越高。
                3. 如果回答只是概念解释，没有个人做法、数据、失败案例或取舍，风险标记 GENERIC_ANSWER。
                4. 如果回答和简历片段明显不一致，风险标记 INCONSISTENT_WITH_RESUME。
                5. 如果用户声称很强但没有证据，风险标记 OVERCLAIMED。
                6. 不要输出 Markdown，只输出 JSON 对象。

                JSON 格式：
                {
                  "normalizedActivityType": "RESUME_INTERVIEW_ANSWER",
                  "activityDifficulty": 60,
                  "activityDifficultyConfidence": 0.7,
                  "completionQuality": 0.7,
                  "completionQualityConfidence": 0.7,
                  "personalContribution": 0.6,
                  "personalContributionConfidence": 0.7,
                  "assessmentConfidence": 0.65,
                  "dimensions": [
                    {
                      "dimension": "RAG 检索与重排能力",
                      "normalizedDimension": "rag-retrieval-rerank",
                      "relevance": 0.9,
                      "confidence": 0.7,
                      "claimedOutcome": "该回答证明了什么",
                      "evidenceRefs": ["answer-1", "project-1"]
                    }
                  ],
                  "evidenceFindings": [
                    {
                      "evidenceId": "answer-1",
                      "evidenceType": "INTERVIEW_ANSWER",
                      "quality": 0.7,
                      "verificationStatus": "SELF_REPORTED",
                      "supports": ["支持哪些能力"]
                    }
                  ],
                  "noveltyFeatures": {
                    "activityFamily": "resume-interview",
                    "techniques": ["RAG", "rerank"],
                    "difficultyBand": "INTERVIEW_EVIDENCE"
                  },
                  "riskFlags": [],
                  "judgeRecommendation": {
                    "suggestReview": false,
                    "reasons": []
                  }
                }
                """;
    }

    private double concreteSignal(String answerText) {
        String normalized = answerText.toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String signal : List.of(
                "因为", "所以", "比如", "例如", "我负责", "我设计", "我实现", "我调试", "指标",
                "延迟", "吞吐", "recall", "ndcg", "mrr", "ab", "事务", "幂等", "失败", "重试",
                "tradeoff", "fallback", "trace", "评测"
        )) {
            if (normalized.contains(signal)) {
                score += 0.08;
            }
        }
        return Math.min(score, 1.0);
    }

    private List<String> strings(List<String> values, int maxSize, int maxLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String cleaned = compact(value, maxLength);
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
            if (result.size() >= maxSize) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private List<String> append(List<String> values, String value, int maxSize) {
        LinkedHashSet<String> result = new LinkedHashSet<>(values == null ? List.of() : values);
        if (value != null && !value.isBlank()) {
            result.add(value);
        }
        return result.stream().limit(maxSize).toList();
    }

    private BigDecimal clamp01(BigDecimal value, BigDecimal fallback) {
        return clamp(value, ZERO, ONE, fallback);
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max, BigDecimal fallback) {
        BigDecimal result = value == null ? fallback : value;
        if (result == null) {
            result = min;
        }
        if (result.compareTo(min) < 0) {
            return min;
        }
        if (result.compareTo(max) > 0) {
            return max;
        }
        return result;
    }

    private BigDecimal bd(double value) {
        return clamp01(BigDecimal.valueOf(value), new BigDecimal("0.50"));
    }

    private String normalizeDimension(String value) {
        String text = Normalizer.normalize(safe(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", "");
        return text.isBlank() ? "resume-interview" : compact(text, 100);
    }

    private String cleanAnswer(String answerText) {
        if (answerText == null) {
            return "";
        }
        return answerText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private String firstPresent(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : safe(second);
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize resume answer evidence", ex);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String preview(String value) {
        return compact(value == null ? "" : value.replaceAll("\\s+", " "), 220);
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String compact(String value, int maxLength) {
        String text = safe(value);
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private record AssessmentOutput(
            EvidenceAssessmentDtos.ModelResponse response,
            String mode
    ) {
    }
}
