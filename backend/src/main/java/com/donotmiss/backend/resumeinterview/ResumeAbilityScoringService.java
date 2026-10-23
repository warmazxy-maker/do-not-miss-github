package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.abilityscore.*;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.mq.DomainEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ResumeAbilityScoringService {
    private static final String PROMPT_VERSION = ResumeAnswerEvidenceEvaluatorService.PROMPT_VERSION;
    private static final String RUBRIC_VERSION = EvidenceAssessmentAgentService.RUBRIC_VERSION;
    private static final String MODEL_NAME = "resume-answer-evidence";

    private final ResumeInterviewSessionRepository sessionRepository;
    private final ResumeInterviewEvidenceRepository evidenceRepository;
    private final ResumeInterviewScoreSummaryRepository scoreSummaryRepository;
    private final ResumeInterviewStateService stateService;
    private final ResumeInterviewService resumeInterviewService;
    private final AbilityAssessmentJobRepository jobRepository;
    private final EvidenceAssessmentPersistenceService persistenceService;
    private final AbilityScoringService abilityScoringService;
    private final AgentRunService agentRunService;
    private final DomainEventPublisher domainEventPublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ResumeAbilityScoringService(ResumeInterviewSessionRepository sessionRepository,
                                       ResumeInterviewEvidenceRepository evidenceRepository,
                                       ResumeInterviewScoreSummaryRepository scoreSummaryRepository,
                                       ResumeInterviewStateService stateService,
                                       ResumeInterviewService resumeInterviewService,
                                       AbilityAssessmentJobRepository jobRepository,
                                       EvidenceAssessmentPersistenceService persistenceService,
                                       AbilityScoringService abilityScoringService,
                                       AgentRunService agentRunService,
                                       DomainEventPublisher domainEventPublisher,
                                       ObjectMapper objectMapper,
                                       TransactionTemplate transactionTemplate) {
        this.domainEventPublisher = domainEventPublisher;
        this.sessionRepository = sessionRepository;
        this.evidenceRepository = evidenceRepository;
        this.scoreSummaryRepository = scoreSummaryRepository;
        this.stateService = stateService;
        this.resumeInterviewService = resumeInterviewService;
        this.jobRepository = jobRepository;
        this.persistenceService = persistenceService;
        this.abilityScoringService = abilityScoringService;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ResumeInterviewDtos.ResumeAssessmentDetailResponse score(String userId, Long sessionId) {
        String normalizedUserId = normalizeUserId(userId);
        ResumeInterviewSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, normalizedUserId)
                .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在"));
        if (session.getStatus() == ResumeInterviewSessionStatus.SCORED
                || session.getStatus() == ResumeInterviewSessionStatus.COMPLETED) {
            return resumeInterviewService.getDetail(normalizedUserId, sessionId);
        }
        if (session.getStatus() != ResumeInterviewSessionStatus.EVIDENCE_READY) {
            throw ApiException.badRequest("当前状态不能计算初始能力分：" + session.getStatus());
        }

        Long runId = agentRunService.startRun(
                normalizedUserId,
                AgentRunType.RESUME_ABILITY_SCORING,
                "简历面试初始定段：" + session.getTargetRole(),
                "sessionId=" + session.getId()
        );
        Long materialStep = null;
        Long scoringStep = null;
        Long summaryStep = null;
        try {
            materialStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_SCORING_MATERIAL,
                    "Load resume evidence candidates and convert to AbilityEvidenceAssessment materials."
            );
            ResumeInterviewScoreSummaryEntity summary = markSummaryScoring(session, runId);
            List<ResumeInterviewEvidenceEntity> candidates =
                    evidenceRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
            List<ResumeInterviewEvidenceEntity> readyCandidates = candidates.stream()
                    .filter(candidate -> candidate.getStatus() == ResumeInterviewEvidenceStatus.EVIDENCE_READY
                            || candidate.getStatus() == ResumeInterviewEvidenceStatus.SCORED)
                    .toList();
            if (readyCandidates.isEmpty()) {
                throw ApiException.badRequest("没有可评分的简历面试证据");
            }
            agentRunService.completeStep(materialStep, "candidates=" + readyCandidates.size());
            materialStep = null;

            scoringStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_SCORE_APPLY,
                    "Run Java AbilityScoreEngine for each evidence candidate."
            );
            List<AbilityScoringExecution> executions = new ArrayList<>();
            List<Long> allResultIds = new ArrayList<>();
            List<Map<String, Object>> judgeTriggered = new ArrayList<>();
            for (ResumeInterviewEvidenceEntity candidate : readyCandidates) {
                CandidateScoreResult result = scoreCandidate(normalizedUserId, session, candidate);
                executions.add(result.execution());
                allResultIds.addAll(result.resultIds());
                judgeTriggered.addAll(result.judgeTriggered());
            }
            agentRunService.completeStep(
                    scoringStep,
                    "executions=" + executions.size()
                            + ", resultIds=" + allResultIds.size()
                            + ", judgeTriggered=" + judgeTriggered.size()
            );
            scoringStep = null;

            summaryStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_SCORE_SUMMARY,
                    "Persist resume scoring summary and advance session to SCORED."
            );
            finishSummary(session, summary, executions, allResultIds, judgeTriggered);
            agentRunService.completeStep(summaryStep, "summaryId=" + summary.getId());
            summaryStep = null;
            agentRunService.finishRun(runId, "resumeScored=true, resultIds=" + allResultIds.size());
            return resumeInterviewService.getDetail(normalizedUserId, sessionId);
        } catch (RuntimeException | Error ex) {
            if (materialStep != null) {
                agentRunService.failStep(materialStep, ex);
            }
            if (scoringStep != null) {
                agentRunService.failStep(scoringStep, ex);
            }
            if (summaryStep != null) {
                agentRunService.failStep(summaryStep, ex);
            }
            markSummaryFailed(session, ex);
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private CandidateScoreResult scoreCandidate(String userId,
                                                ResumeInterviewSessionEntity session,
                                                ResumeInterviewEvidenceEntity candidate) {
        if (candidate.getStatus() == ResumeInterviewEvidenceStatus.SCORED
                && candidate.getAbilityScoreResultIdsJson() != null
                && !"[]".equals(candidate.getAbilityScoreResultIdsJson())) {
            List<Long> ids = readLongList(candidate.getAbilityScoreResultIdsJson());
            return new CandidateScoreResult(
                    new AbilityScoringExecution(candidate.getAbilityAssessmentId(), "", ids.size(), 0, List.of()),
                    ids,
                    List.of()
            );
        }

        ResumeInterviewDtos.UnifiedAbilityEvidence unified = readUnifiedEvidence(candidate);
        EvidenceAssessmentDtos.ModelResponse assessment = unified.assessment();
        if (assessment == null || assessment.dimensions() == null || assessment.dimensions().isEmpty()) {
            throw ApiException.badRequest("简历面试证据缺少能力维度，candidateId=" + candidate.getId());
        }

        AbilityAssessmentJobEntity job = findOrCreateJob(userId, candidate, unified);
        EvidenceAssessmentPersistenceService.EvidenceAssessmentMaterial material = toMaterial(unified, assessment);
        AbilityEvidenceAssessmentEntity savedAssessment = persistenceService.saveAssessment(job.getId(), material);
        AbilityScoringExecution execution = abilityScoringService.score(new AbilityScoringCommand(
                savedAssessment.getId(),
                AbilityEvidenceSourceType.RESUME_INTERVIEW,
                evidenceQuality(assessment),
                novelty(assessment),
                "resume-interview:" + session.getId()
        ));
        List<Long> resultIds = execution.results().stream()
                .map(AbilityScoringExecution.DimensionResult::scoreResultId)
                .toList();
        List<Map<String, Object>> judgeTriggered = execution.results().stream()
                .filter(result -> result.status() == AbilityScoreResultStatus.REVIEW_REQUIRED)
                .map(result -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("candidateId", candidate.getId());
                    item.put("scoreResultId", result.scoreResultId());
                    item.put("dimension", result.dimension());
                    item.put("newAbilityScore", result.newAbilityScore());
                    item.put("newUncertainty", result.newAbilityUncertainty());
                    item.put("judgeFlags", result.judgeFlags());
                    return item;
                })
                .toList();

        transactionTemplate.execute(status -> {
            ResumeInterviewEvidenceEntity entity = evidenceRepository.findById(candidate.getId())
                    .orElseThrow(() -> ApiException.notFound("简历面试证据不存在：" + candidate.getId()));
            entity.setStatus(ResumeInterviewEvidenceStatus.SCORED);
            entity.setAbilityAssessmentId(savedAssessment.getId());
            entity.setAbilityScoreResultIdsJson(toJson(resultIds));
            evidenceRepository.save(entity);
            // V1 stays the synchronous result of this request; V2 (dual labels) follows through the
            // outbox. Same transaction, so a rollback here never leaves a V2 message for an unscored row.
            domainEventPublisher.publishResumeCapabilityV2Scoring(
                    entity.getId(),
                    userId,
                    savedAssessment.getId(),
                    evidenceQuality(assessment),
                    novelty(assessment)
            );
            return null;
        });
        return new CandidateScoreResult(execution, resultIds, judgeTriggered);
    }

    private AbilityAssessmentJobEntity findOrCreateJob(String userId,
                                                       ResumeInterviewEvidenceEntity candidate,
                                                       ResumeInterviewDtos.UnifiedAbilityEvidence unified) {
        return jobRepository.findFirstByUserIdAndEvidenceHashAndPromptVersionAndRubricVersionOrderByCreatedAtDesc(
                        userId,
                        unified.evidenceHash(),
                        PROMPT_VERSION,
                        RUBRIC_VERSION
                )
                .orElseGet(() -> {
                    AbilityAssessmentJobEntity job = new AbilityAssessmentJobEntity();
                    job.setRequestId(UUID.randomUUID().toString());
                    job.setUserId(userId);
                    job.setAchievementRecordId(null);
                    job.setStatus(AbilityAssessmentJobStatus.PENDING);
                    job.setFairnessStatus(AbilityAssessmentFairnessStatus.CLEAR);
                    job.setEvidenceHash(unified.evidenceHash());
                    job.setContentFingerprint(unified.evidenceHash());
                    job.setPromptVersion(PROMPT_VERSION);
                    job.setRubricVersion(RUBRIC_VERSION);
                    job.setModelName(MODEL_NAME);
                    job.setInputSnapshotJson(candidate.getAbilityEvidenceJson());
                    return jobRepository.save(job);
                });
    }

    private EvidenceAssessmentPersistenceService.EvidenceAssessmentMaterial toMaterial(
            ResumeInterviewDtos.UnifiedAbilityEvidence unified,
            EvidenceAssessmentDtos.ModelResponse assessment
    ) {
        return new EvidenceAssessmentPersistenceService.EvidenceAssessmentMaterial(
                compact(firstPresent(assessment.normalizedActivityType(), "RESUME_INTERVIEW_ANSWER"), 48),
                nonNull(assessment.activityDifficulty(), new BigDecimal("50.0000")),
                unit(assessment.activityDifficultyConfidence(), new BigDecimal("0.5000")),
                unit(assessment.completionQuality(), new BigDecimal("0.5000")),
                unit(assessment.completionQualityConfidence(), new BigDecimal("0.5000")),
                unit(assessment.personalContribution(), new BigDecimal("0.5000")),
                unit(assessment.personalContributionConfidence(), new BigDecimal("0.5000")),
                unit(assessment.assessmentConfidence(), new BigDecimal("0.5000")),
                toJson(assessment.evidenceFindings() == null ? List.of() : assessment.evidenceFindings()),
                toJson(assessment.noveltyFeatures() == null
                        ? new EvidenceAssessmentDtos.NoveltyFeatures("resume-interview", List.of(), "INTERVIEW_EVIDENCE")
                        : assessment.noveltyFeatures()),
                toJson(assessment.riskFlags() == null ? List.of() : assessment.riskFlags()),
                toJson(assessment.judgeRecommendation() == null
                        ? new EvidenceAssessmentDtos.JudgeRecommendation(false, List.of())
                        : assessment.judgeRecommendation()),
                toJson(assessment),
                unified.evidenceHash(),
                PROMPT_VERSION,
                RUBRIC_VERSION,
                MODEL_NAME,
                assessment.dimensions().stream()
                        .map(this::toDimensionMaterial)
                        .toList()
        );
    }

    private EvidenceAssessmentPersistenceService.EvidenceAssessmentDimensionMaterial toDimensionMaterial(
            EvidenceAssessmentDtos.DimensionItem item
    ) {
        String dimensionName = compact(firstPresent(item.dimension(), item.normalizedDimension()), 100);
        String normalizedDimension = compact(firstPresent(item.normalizedDimension(), dimensionName), 100);
        return new EvidenceAssessmentPersistenceService.EvidenceAssessmentDimensionMaterial(
                dimensionName,
                normalizedDimension,
                unit(item.relevance(), new BigDecimal("0.5000")),
                unit(item.confidence(), new BigDecimal("0.5000")),
                compact(item.claimedOutcome(), 600),
                toJson(item.evidenceRefs() == null ? List.of() : item.evidenceRefs())
        );
    }

    private ResumeInterviewScoreSummaryEntity markSummaryScoring(ResumeInterviewSessionEntity session, Long runId) {
        return transactionTemplate.execute(status -> {
            ResumeInterviewScoreSummaryEntity summary = scoreSummaryRepository.findBySessionId(session.getId())
                    .orElseGet(() -> {
                        ResumeInterviewScoreSummaryEntity entity = new ResumeInterviewScoreSummaryEntity();
                        entity.setSessionId(session.getId());
                        return entity;
                    });
            if (summary.getStatus() == ResumeInterviewScoreStatus.SCORED) {
                return summary;
            }
            summary.setStatus(ResumeInterviewScoreStatus.SCORING);
            summary.setErrorMessage(null);
            summary.setScoringTraceRunId(runId);
            return scoreSummaryRepository.save(summary);
        });
    }

    private void finishSummary(ResumeInterviewSessionEntity session,
                               ResumeInterviewScoreSummaryEntity summary,
                               List<AbilityScoringExecution> executions,
                               List<Long> resultIds,
                               List<Map<String, Object>> judgeTriggered) {
        transactionTemplate.execute(status -> {
            ResumeInterviewScoreSummaryEntity entity = scoreSummaryRepository.findById(summary.getId())
                    .orElseThrow(() -> ApiException.notFound("简历定段结果不存在：" + summary.getId()));
            entity.setStatus(ResumeInterviewScoreStatus.SCORED);
            entity.setAbilityScoreResultIdsJson(toJson(resultIds.stream().distinct().toList()));
            entity.setJudgeTriggeredJson(toJson(judgeTriggered));
            entity.setScoreSummaryJson(toJson(summaryPayload(executions, resultIds, judgeTriggered)));
            entity.setScoringRuleVersion(AbilityScoringPolicyV2.RULE_VERSION);
            entity.setCompletedAt(Instant.now());
            scoreSummaryRepository.save(entity);

            ResumeInterviewSessionEntity freshSession = sessionRepository.findById(session.getId())
                    .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在：" + session.getId()));
            freshSession.setFinalScoreSummaryId(entity.getId());
            if (freshSession.getStatus() == ResumeInterviewSessionStatus.EVIDENCE_READY) {
                stateService.moveTo(freshSession, ResumeInterviewSessionStatus.SCORED);
            }
            sessionRepository.save(freshSession);
            return null;
        });
    }

    private Map<String, Object> summaryPayload(List<AbilityScoringExecution> executions,
                                               List<Long> resultIds,
                                               List<Map<String, Object>> judgeTriggered) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scoringRuleVersion", AbilityScoringPolicyV2.RULE_VERSION);
        payload.put("assessmentCount", executions.size());
        payload.put("scoreResultCount", resultIds.size());
        payload.put("judgeTriggeredCount", judgeTriggered.size());
        payload.put("resultIds", resultIds.stream().distinct().toList());
        payload.put("executions", executions);
        return payload;
    }

    private void markSummaryFailed(ResumeInterviewSessionEntity session, Throwable error) {
        transactionTemplate.execute(status -> {
            ResumeInterviewScoreSummaryEntity summary = scoreSummaryRepository.findBySessionId(session.getId())
                    .orElseGet(() -> {
                        ResumeInterviewScoreSummaryEntity entity = new ResumeInterviewScoreSummaryEntity();
                        entity.setSessionId(session.getId());
                        return entity;
                    });
            summary.setStatus(ResumeInterviewScoreStatus.FAILED);
            summary.setErrorMessage(compact(errorMessage(error), 1200));
            scoreSummaryRepository.save(summary);
            return null;
        });
    }

    private ResumeInterviewDtos.UnifiedAbilityEvidence readUnifiedEvidence(ResumeInterviewEvidenceEntity candidate) {
        try {
            return objectMapper.readValue(candidate.getAbilityEvidenceJson(), ResumeInterviewDtos.UnifiedAbilityEvidence.class);
        } catch (Exception ex) {
            throw ApiException.badRequest("简历面试证据 JSON 无法读取，candidateId=" + candidate.getId());
        }
    }

    private BigDecimal evidenceQuality(EvidenceAssessmentDtos.ModelResponse assessment) {
        if (assessment.evidenceFindings() == null || assessment.evidenceFindings().isEmpty()) {
            return new BigDecimal("0.5000");
        }
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (EvidenceAssessmentDtos.EvidenceFinding finding : assessment.evidenceFindings()) {
            if (finding != null && finding.quality() != null) {
                total = total.add(unit(finding.quality(), BigDecimal.ZERO));
                count++;
            }
        }
        if (count == 0) {
            return new BigDecimal("0.5000");
        }
        return total.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal novelty(EvidenceAssessmentDtos.ModelResponse assessment) {
        List<String> risks = assessment.riskFlags() == null ? List.of() : assessment.riskFlags();
        if (risks.contains("GENERIC_ANSWER") || risks.contains("ANSWER_TOO_SHORT")) {
            return new BigDecimal("0.7000");
        }
        return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
    }

    private List<Long> readLongList(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private BigDecimal unit(BigDecimal value, BigDecimal fallback) {
        BigDecimal actual = value == null ? fallback : value;
        return actual.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal nonNull(BigDecimal value, BigDecimal fallback) {
        return (value == null ? fallback : value).setScale(4, RoundingMode.HALF_UP);
    }

    private String firstPresent(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : safe(second);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("简历定段评分 JSON 序列化失败", ex);
        }
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String compact(String value, int maxLength) {
        String text = safe(value);
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record CandidateScoreResult(
            AbilityScoringExecution execution,
            List<Long> resultIds,
            List<Map<String, Object>> judgeTriggered
    ) {
    }
}
