package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AbilityScoringService {
    private static final BigDecimal MIN_RELEVANCE = new BigDecimal("0.3000");
    private static final BigDecimal DEFAULT_PROFILE_CONFIDENCE = new BigDecimal("0.5000");
    private static final BigDecimal DEFAULT_EVIDENCE_QUALITY = new BigDecimal("0.5000");
    private static final BigDecimal DEFAULT_NOVELTY = new BigDecimal("1.0000");

    private final AbilityScoreCalculator calculator;
    private final AbilityAssessmentJobRepository jobRepository;
    private final AbilityEvidenceAssessmentRepository assessmentRepository;
    private final AbilityEvidenceDimensionRepository dimensionRepository;
    private final AbilityScoringProfileRepository profileRepository;
    private final UserAbilityStateRepository stateRepository;
    private final AbilityScoreResultRepository resultRepository;
    private final AbilityFairnessPolicy fairnessPolicy;
    private final AbilityScoreAppealService appealService;
    private final JudgeAssessmentService judgeAssessmentService;
    private final ObjectMapper objectMapper;

    public AbilityScoringService(AbilityScoreCalculator calculator,
                                 AbilityAssessmentJobRepository jobRepository,
                                 AbilityEvidenceAssessmentRepository assessmentRepository,
                                 AbilityEvidenceDimensionRepository dimensionRepository,
                                 AbilityScoringProfileRepository profileRepository,
                                 UserAbilityStateRepository stateRepository,
                                 AbilityScoreResultRepository resultRepository,
                                 AbilityFairnessPolicy fairnessPolicy,
                                 AbilityScoreAppealService appealService,
                                 JudgeAssessmentService judgeAssessmentService,
                                 ObjectMapper objectMapper) {
        this.calculator = calculator;
        this.jobRepository = jobRepository;
        this.assessmentRepository = assessmentRepository;
        this.dimensionRepository = dimensionRepository;
        this.profileRepository = profileRepository;
        this.stateRepository = stateRepository;
        this.resultRepository = resultRepository;
        this.fairnessPolicy = fairnessPolicy;
        this.appealService = appealService;
        this.judgeAssessmentService = judgeAssessmentService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AbilityScoringExecution score(AbilityScoringCommand command) {
        if (command == null || command.assessmentId() == null) {
            throw ApiException.badRequest("assessmentId 不能为空");
        }

        AbilityEvidenceAssessmentEntity assessment = assessmentRepository.findById(command.assessmentId())
                .orElseThrow(() -> ApiException.notFound("能力证据评估不存在：" + command.assessmentId()));
        AbilityAssessmentJobEntity job = jobRepository.findById(assessment.getJobId())
                .orElseThrow(() -> ApiException.notFound("能力证据任务不存在：" + assessment.getJobId()));
        List<AbilityEvidenceDimensionEntity> dimensions =
                dimensionRepository.findByAssessmentIdOrderByRelevanceDesc(assessment.getId());

        AbilityScoringProfileEntity profile = profileRepository.findById(job.getUserId())
                .orElseGet(() -> createProfile(job.getUserId()));

        AbilityEvidenceSourceType sourceType = command.sourceType() == null
                ? AbilityEvidenceSourceType.OTHER
                : command.sourceType();
        BigDecimal evidenceQuality = unitOrDefault(command.evidenceQuality(), DEFAULT_EVIDENCE_QUALITY);
        BigDecimal novelty = unitOrDefault(command.novelty(), DEFAULT_NOVELTY);
        String historyVersion = command.historySnapshotVersion() == null
                || command.historySnapshotVersion().isBlank()
                ? "history-none"
                : command.historySnapshotVersion().trim();

        List<AbilityScoringExecution.DimensionResult> executionResults = new ArrayList<>();
        int skippedDimensions = 0;
        boolean reviewRequired = false;
        List<AbilityEvidenceDimensionEntity> eligibleDimensions = dimensions.stream()
                .filter(dimension -> dimension.getRelevance() != null
                        && dimension.getRelevance().compareTo(MIN_RELEVANCE) >= 0)
                .toList();
        AbilityFairnessPolicy.DimensionBudget dimensionBudget = fairnessPolicy.normalizeDimensionBudget(
                eligibleDimensions.stream().map(AbilityEvidenceDimensionEntity::getRelevance).toList()
        );
        Map<Long, BigDecimal> adjustedRelevanceByDimension = new LinkedHashMap<>();
        for (int index = 0; index < eligibleDimensions.size(); index++) {
            adjustedRelevanceByDimension.put(
                    eligibleDimensions.get(index).getId(),
                    dimensionBudget.adjustedRelevance().get(index)
            );
        }
        if (dimensionBudget.normalized()
                && job.getFairnessStatus() == AbilityAssessmentFairnessStatus.CLEAR) {
            job.setFairnessStatus(AbilityAssessmentFairnessStatus.MULTI_DIMENSION_NORMALIZED);
        }

        for (AbilityEvidenceDimensionEntity dimension : dimensions) {
            if (dimension.getRelevance() == null || dimension.getRelevance().compareTo(MIN_RELEVANCE) < 0) {
                skippedDimensions++;
                continue;
            }

            var existing = resultRepository
                    .findByAssessmentIdAndNormalizedDimensionAndScoringRuleVersion(
                            assessment.getId(),
                            dimension.getNormalizedDimension(),
                            AbilityScoringPolicyV2.RULE_VERSION
                    );
            if (existing.isPresent()) {
                if (existing.get().getStatus() == AbilityScoreResultStatus.REVIEW_REQUIRED) {
                    judgeAssessmentService.createIfRequired(
                            existing.get(),
                            readStringList(existing.get().getJudgeFlagsJson())
                    );
                }
                executionResults.add(toExecutionResult(existing.get(), true, false));
                reviewRequired |= existing.get().getStatus() == AbilityScoreResultStatus.REVIEW_REQUIRED;
                continue;
            }

            var existingRecordScore = resultRepository
                    .findTopByAchievementRecordIdAndNormalizedDimensionAndScoringRuleVersionAndStatusNotOrderByCreatedAtDesc(
                            job.getAchievementRecordId(),
                            dimension.getNormalizedDimension(),
                            AbilityScoringPolicyV2.RULE_VERSION,
                            AbilityScoreResultStatus.SUPERSEDED
                    );
            UserAbilityStateEntity state;
            AbilityScoreResultEntity priorResult = existingRecordScore.orElse(null);
            if (priorResult != null) {
                state = stateRepository.findById(priorResult.getAbilityStateId())
                        .orElseThrow(() -> ApiException.notFound("能力状态不存在：" + priorResult.getAbilityStateId()));
                AbilityScoreResultEntity latestActive = resultRepository
                        .findFirstByAbilityStateIdAndStatusNotOrderByCreatedAtDesc(
                                state.getId(),
                                AbilityScoreResultStatus.SUPERSEDED
                        )
                        .orElse(priorResult);
                if (!latestActive.getId().equals(priorResult.getId())) {
                    appealService.createReplayRequest(
                            job.getUserId(),
                            priorResult,
                            assessment.getId(),
                            "该成就证据发生变化，但同一能力之后已有其他评分，必须按时间顺序回放后再安全更新"
                    );
                    executionResults.add(toExecutionResult(priorResult, true, true));
                    reviewRequired = true;
                    continue;
                }
            } else {
                state = stateRepository
                        .findByUserIdAndNormalizedDimension(job.getUserId(), dimension.getNormalizedDimension())
                        .orElseGet(() -> createState(job.getUserId(), dimension));
            }

            BigDecimal extractionConfidence = minimum(
                    assessment.getAssessmentConfidence(),
                    assessment.getActivityDifficultyConfidence(),
                    assessment.getCompletionQualityConfidence(),
                    dimension.getRelevanceConfidence()
            );

            BigDecimal baseExperience = priorResult == null
                    ? state.getExperienceScore() : priorResult.getOldExperienceScore();
            BigDecimal baseAbility = priorResult == null
                    ? state.getAbilityScore() : priorResult.getOldAbilityScore();
            BigDecimal baseUncertainty = priorResult == null
                    ? state.getAbilityUncertainty() : priorResult.getOldAbilityUncertainty();
            String baseRank = priorResult == null
                    ? state.getRankName()
                    : readString(priorResult.getFactorSnapshotJson(), "oldRank", state.getRankName());
            BigDecimal adjustedRelevance = adjustedRelevanceByDimension.getOrDefault(
                    dimension.getId(),
                    dimension.getRelevance()
            );

            AbilityScoringModels.ScoreOutput output = calculator.calculate(new AbilityScoringModels.ScoreInput(
                    baseExperience,
                    baseAbility,
                    baseUncertainty,
                    baseRank,
                    profile.getProfileConfidence(),
                    assessment.getActivityDifficulty(),
                    assessment.getCompletionQuality(),
                    assessment.getPersonalContribution(),
                    assessment.getPersonalContributionConfidence(),
                    adjustedRelevance,
                    evidenceQuality,
                    extractionConfidence,
                    novelty,
                    sourceType
            ));

            AbilityScoreResultStatus status;
            if (output.requiresJudgeReview()) {
                status = AbilityScoreResultStatus.REVIEW_REQUIRED;
            } else if (output.verificationStrength()
                    .compareTo(AbilityScoringPolicyV2.VERIFIED_STATUS_THRESHOLD) >= 0) {
                status = AbilityScoreResultStatus.VERIFIED;
            } else {
                status = AbilityScoreResultStatus.PROVISIONAL;
            }
            AbilityScoreResultEntity result = persistResult(
                    job,
                    assessment,
                    dimension,
                    state,
                    profile,
                    output,
                    status,
                    historyVersion,
                    evidenceQuality,
                    novelty,
                    dimension.getRelevance(),
                    dimensionBudget.multiplier(),
                    priorResult == null ? null : priorResult.getId()
            );

            if (priorResult != null) {
                priorResult.setStatus(AbilityScoreResultStatus.SUPERSEDED);
                resultRepository.save(priorResult);
            }
            state.setExperienceScore(output.newExperienceScore());
            state.setAbilityScore(output.newAbilityScore());
            state.setAbilityUncertainty(output.newAbilityUncertainty());
            if (!output.requiresJudgeReview() || output.oldRank().equals(output.proposedRank())) {
                state.setRankName(output.proposedRank());
            }
            stateRepository.save(state);
            if (result.getStatus() == AbilityScoreResultStatus.REVIEW_REQUIRED) {
                judgeAssessmentService.createIfRequired(result, output.judgeFlags());
            }

            executionResults.add(toExecutionResult(result, false, false));
            reviewRequired |= output.requiresJudgeReview();
        }

        job.setStatus(reviewRequired
                ? AbilityAssessmentJobStatus.REVIEW_REQUIRED
                : AbilityAssessmentJobStatus.COMPLETED);
        jobRepository.save(job);

        return new AbilityScoringExecution(
                assessment.getId(),
                job.getRequestId(),
                executionResults.size(),
                skippedDimensions,
                List.copyOf(executionResults)
        );
    }

    private AbilityScoringProfileEntity createProfile(String userId) {
        AbilityScoringProfileEntity profile = new AbilityScoringProfileEntity();
        profile.setUserId(userId);
        profile.setProfileConfidence(DEFAULT_PROFILE_CONFIDENCE);
        return profileRepository.save(profile);
    }

    private UserAbilityStateEntity createState(String userId, AbilityEvidenceDimensionEntity dimension) {
        UserAbilityStateEntity state = new UserAbilityStateEntity();
        state.setUserId(userId);
        state.setDimensionName(dimension.getDimensionName());
        state.setNormalizedDimension(dimension.getNormalizedDimension());
        state.setExperienceScore(BigDecimal.ZERO);
        state.setAbilityScore(BigDecimal.ZERO);
        state.setAbilityUncertainty(BigDecimal.ONE);
        state.setRankName("UNRATED");
        return stateRepository.save(state);
    }

    private AbilityScoreResultEntity persistResult(AbilityAssessmentJobEntity job,
                                                   AbilityEvidenceAssessmentEntity assessment,
                                                   AbilityEvidenceDimensionEntity dimension,
                                                   UserAbilityStateEntity state,
                                                   AbilityScoringProfileEntity profile,
                                                   AbilityScoringModels.ScoreOutput output,
                                                   AbilityScoreResultStatus status,
                                                   String historyVersion,
                                                   BigDecimal evidenceQuality,
                                                   BigDecimal novelty,
                                                   BigDecimal originalDimensionRelevance,
                                                   BigDecimal dimensionBudgetMultiplier,
                                                   Long supersedesResultId) {
        Map<String, Object> snapshot = new LinkedHashMap<>(output.factorSnapshot());
        snapshot.put("assessmentId", assessment.getId());
        snapshot.put("assessmentEvidenceHash", assessment.getEvidenceHash());
        snapshot.put("dimensionRelevanceConfidence", dimension.getRelevanceConfidence());
        snapshot.put("originalDimensionRelevance", originalDimensionRelevance);
        snapshot.put("adjustedDimensionRelevance", output.factorSnapshot().get("relevance"));
        snapshot.put("dimensionBudgetMultiplier", dimensionBudgetMultiplier);
        snapshot.put("evidenceQualityInput", evidenceQuality);
        snapshot.put("noveltyInput", novelty);
        snapshot.put("difficultyDifference", output.difficultyDifference());
        snapshot.put("abilityGain", output.abilityGain());
        snapshot.put("oldRank", output.oldRank());
        snapshot.put("proposedRank", output.proposedRank());

        AbilityScoreResultEntity result = new AbilityScoreResultEntity();
        result.setRequestId(job.getRequestId());
        result.setUserId(job.getUserId());
        result.setAchievementRecordId(job.getAchievementRecordId());
        result.setAssessmentId(assessment.getId());
        result.setAbilityStateId(state.getId());
        result.setDimensionName(dimension.getDimensionName());
        result.setNormalizedDimension(dimension.getNormalizedDimension());
        result.setStatus(status);
        result.setOldExperienceScore(output.oldExperienceScore());
        result.setProvisionalExperienceGain(output.provisionalExperienceGain());
        result.setVerifiedExperienceGain(output.verifiedExperienceGain());
        result.setNewExperienceScore(output.newExperienceScore());
        result.setOldAbilityScore(output.oldAbilityScore());
        result.setNewAbilityScore(output.newAbilityScore());
        result.setOldAbilityUncertainty(output.oldAbilityUncertainty());
        result.setNewAbilityUncertainty(output.newAbilityUncertainty());
        result.setProfileConfidenceSnapshot(profile.getProfileConfidence());
        result.setGrowthValue(output.growthValue());
        result.setVerificationStrength(output.verificationStrength());
        result.setProfileConfidenceMultiplier(output.profileConfidenceMultiplier());
        result.setDifficultyMatchMultiplier(output.difficultyMatchMultiplier());
        result.setFactorSnapshotJson(toJson(snapshot));
        result.setJudgeFlagsJson(toJson(output.judgeFlags()));
        result.setScoringRuleVersion(output.scoringRuleVersion());
        result.setHistorySnapshotVersion(historyVersion);
        result.setSupersedesResultId(supersedesResultId);
        return resultRepository.save(result);
    }

    private AbilityScoringExecution.DimensionResult toExecutionResult(AbilityScoreResultEntity result,
                                                                      boolean reused,
                                                                      boolean correctionPending) {
        UserAbilityStateEntity state = stateRepository.findById(result.getAbilityStateId())
                .orElseThrow(() -> ApiException.notFound("能力状态不存在：" + result.getAbilityStateId()));
        List<String> flags = readStringList(result.getJudgeFlagsJson());
        String proposedRank = readString(result.getFactorSnapshotJson(), "proposedRank", state.getRankName());
        return new AbilityScoringExecution.DimensionResult(
                result.getId(),
                state.getId(),
                result.getDimensionName(),
                result.getVerifiedExperienceGain(),
                result.getNewAbilityScore(),
                result.getNewAbilityUncertainty(),
                state.getRankName(),
                proposedRank,
                result.getStatus(),
                flags,
                reused,
                correctionPending
        );
    }

    private BigDecimal minimum(BigDecimal... values) {
        BigDecimal minimum = BigDecimal.ONE;
        for (BigDecimal value : values) {
            if (value != null) {
                minimum = minimum.min(unitOrDefault(value, BigDecimal.ZERO));
            }
        }
        return minimum;
    }

    private BigDecimal unitOrDefault(BigDecimal value, BigDecimal fallback) {
        BigDecimal actual = value == null ? fallback : value;
        return actual.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("能力评分审计 JSON 序列化失败", ex);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(
                    json == null ? "[]" : json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String readString(String json, String field, String fallback) {
        try {
            var node = objectMapper.readTree(json == null ? "{}" : json);
            return node.path(field).asText(fallback);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }
}
