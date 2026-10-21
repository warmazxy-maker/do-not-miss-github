package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceAssessmentEntity;
import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.abilityscore.AbilityFairnessPolicy;
import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import com.donotmiss.backend.abilityscore.AbilityScoringModels;
import com.donotmiss.backend.abilityscore.AbilityScoringPolicyV2;
import com.donotmiss.backend.abilityscore.AbilityScoringProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 11 adapter between dual-label classification and the versioned Java score formula.
 *
 * <p>LLM agents provide bucket relevance and evidence facts only. This service applies the
 * same {@link AbilityScoreCalculator} used by the existing ability system, while keeping a
 * separate V2 state, evidence link and score audit for every selected coarse bucket.</p>
 */
@Service
public class CapabilityV2ScoringService {
    private static final BigDecimal MIN_RELEVANCE = new BigDecimal("0.3000");
    private static final BigDecimal DEFAULT_PROFILE_CONFIDENCE = new BigDecimal("0.5000");
    private static final BigDecimal DEFAULT_EVIDENCE_QUALITY = new BigDecimal("0.5000");
    private static final BigDecimal DEFAULT_NOVELTY = new BigDecimal("1.0000");
    private static final int MAX_BUCKETS_PER_EVIDENCE = 3;
    private static final BigDecimal SECONDARY_ROLE_RELEVANCE_MULTIPLIER = new BigDecimal("0.8500");

    private final AbilityScoreCalculator calculator;
    private final AbilityFairnessPolicy fairnessPolicy;
    private final AbilityScoringProfileRepository profileRepository;
    private final UserCapabilityStateRepository stateRepository;
    private final CapabilityEvidenceLinkRepository evidenceLinkRepository;
    private final CapabilityScoreAuditRepository scoreAuditRepository;
    private final RankPromotionPolicy rankPolicy;
    private final ObjectMapper objectMapper;

    public CapabilityV2ScoringService(
            AbilityScoreCalculator calculator,
            AbilityFairnessPolicy fairnessPolicy,
            AbilityScoringProfileRepository profileRepository,
            UserCapabilityStateRepository stateRepository,
            CapabilityEvidenceLinkRepository evidenceLinkRepository,
            CapabilityScoreAuditRepository scoreAuditRepository,
            RankPromotionPolicy rankPolicy,
            ObjectMapper objectMapper
    ) {
        this.calculator = calculator;
        this.rankPolicy = rankPolicy;
        this.fairnessPolicy = fairnessPolicy;
        this.profileRepository = profileRepository;
        this.stateRepository = stateRepository;
        this.evidenceLinkRepository = evidenceLinkRepository;
        this.scoreAuditRepository = scoreAuditRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists V2 scoring results. The caller is responsible for providing the already
     * validated Evidence Evaluator signals; this method never asks an LLM to calculate points.
     */
    @Transactional
    public ScoringOutcome score(ScoreRequest request) {
        requireEligible(request);
        BigDecimal profileConfidence = profileConfidence(request.evidence().userId());
        ScoringPreview preview = preview(request, profileConfidence);
        List<BucketScoreResult> results = new ArrayList<>();

        for (PlannedBucket planned : preview.plannedBuckets()) {
            String idempotencyKey = bucketIdempotencyKey(request.evidence(), planned.assignment().bucketId());
            Optional<CapabilityEvidenceLinkEntity> existingLink = evidenceLinkRepository
                    .findByBucketIdAndIdempotencyKey(planned.assignment().bucketId(), idempotencyKey);
            if (existingLink.isPresent()) {
                results.add(reusedResult(planned, existingLink.get()));
                continue;
            }

            UserCapabilityStateEntity state = stateRepository
                    .findByUserIdAndBucketId(request.evidence().userId(), planned.assignment().bucketId())
                    .orElseGet(() -> createState(request.evidence().userId(), planned.assignment().bucketId()));
            AbilityScoringModels.ScoreOutput output = calculate(request, planned, state, profileConfidence);
            // Advisory judge flags stay in the audit; only the evidence that starts a promotion hold
            // is marked for review. Later evidence during the same hold is ordinary scored evidence.
            CapabilityEvidenceLinkStatus status = rankPolicy.startsHold(
                    state.getRankName(), output.oldAbilityScore(), output.newAbilityScore())
                    ? CapabilityEvidenceLinkStatus.REVIEW_REQUIRED
                    : CapabilityEvidenceLinkStatus.SCORED;

            applyOutput(state, output);
            state = stateRepository.save(state);

            CapabilityEvidenceLinkEntity link = createEvidenceLink(request, planned, state, idempotencyKey, status);
            link = evidenceLinkRepository.save(link);

            CapabilityScoreAuditEntity audit = createAudit(request, planned, state, link, output, status);
            scoreAuditRepository.save(audit);
            results.add(new BucketScoreResult(
                    planned.assignment().bucketId(),
                    planned.assignment().bucketType(),
                    planned.assignment().bucketName(),
                    planned.assignment().role(),
                    planned.assignment().relevance(),
                    planned.adjustedRelevance(),
                    planned.assignment().relevanceConfidence(),
                    output.verifiedExperienceGain(),
                    output.newAbilityScore(),
                    output.newAbilityUncertainty(),
                    status,
                    output.judgeFlags(),
                    false
            ));
        }
        return new ScoringOutcome(
                request.evidence().evidenceId(),
                preview.skippedAssignments(),
                List.copyOf(results)
        );
    }

    /**
     * A completed business record may be delivered more than once by RabbitMQ. Once any
     * bucket link exists for that user and source, the whole evidence version is treated
     * as already committed. This protects against a later retry classifying a new bucket
     * and accidentally turning one completion into an additional score.
     */
    public boolean hasRecordedBusinessEvidence(CapabilityEvidenceInput evidence) {
        if (evidence == null) {
            return false;
        }
        return evidenceLinkRepository.existsByUserIdAndSourceTypeAndSourceId(
                evidence.userId(),
                evidence.sourceKind().name(),
                evidence.sourceId()
        );
    }

    /**
     * Pure calculation entry point used by tests and by a future dry-run/admin view.
     * It does not read or write a repository.
     */
    public ScoringPreview preview(ScoreRequest request, BigDecimal profileConfidence) {
        requireEligible(request);
        List<PlannedBucket> plannedBuckets = planBuckets(request);
        List<PreviewBucketResult> previewResults = new ArrayList<>();
        for (PlannedBucket planned : plannedBuckets) {
            AbilityScoringModels.ScoreOutput output = calculator.calculate(new AbilityScoringModels.ScoreInput(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    "UNRATED",
                    unit(profileConfidence, DEFAULT_PROFILE_CONFIDENCE),
                    request.signals().activityDifficulty(),
                    request.signals().completionQuality(),
                    request.signals().personalContribution(),
                    request.signals().personalContributionConfidence(),
                    planned.adjustedRelevance(),
                    unit(request.evidenceQuality(), DEFAULT_EVIDENCE_QUALITY),
                    assessmentConfidence(request.signals(), planned.assignment()),
                    unit(request.novelty(), DEFAULT_NOVELTY),
                    sourceType(request.evidence())
            ));
            previewResults.add(new PreviewBucketResult(
                    planned.assignment().bucketId(),
                    planned.assignment().bucketType(),
                    planned.assignment().bucketName(),
                    planned.assignment().role(),
                    planned.assignment().relevance(),
                    planned.adjustedRelevance(),
                    planned.assignment().relevanceConfidence(),
                    output
            ));
        }
        return new ScoringPreview(
                List.copyOf(previewResults),
                List.copyOf(plannedBuckets),
                Math.max(0, allAssignments(request).size() - plannedBuckets.size())
        );
    }

    private AbilityScoringModels.ScoreOutput calculate(
            ScoreRequest request,
            PlannedBucket planned,
            UserCapabilityStateEntity state,
            BigDecimal profileConfidence
    ) {
        return calculator.calculate(new AbilityScoringModels.ScoreInput(
                state.getExperienceScore(),
                state.getAbilityScore(),
                state.getAbilityUncertainty(),
                state.getRankName(),
                profileConfidence,
                request.signals().activityDifficulty(),
                request.signals().completionQuality(),
                request.signals().personalContribution(),
                request.signals().personalContributionConfidence(),
                planned.adjustedRelevance(),
                unit(request.evidenceQuality(), DEFAULT_EVIDENCE_QUALITY),
                assessmentConfidence(request.signals(), planned.assignment()),
                unit(request.novelty(), DEFAULT_NOVELTY),
                sourceType(request.evidence())
        ));
    }

    private List<PlannedBucket> planBuckets(ScoreRequest request) {
        Map<Long, CandidateAssignment> strongestByBucket = new LinkedHashMap<>();
        for (CapabilityV2Dtos.BucketAssignment assignment : allAssignments(request)) {
            if (!isScoreableAssignment(assignment)) {
                continue;
            }
            CandidateAssignment candidate = new CandidateAssignment(assignment, effectiveRelevance(assignment));
            strongestByBucket.merge(assignment.bucketId(), candidate, this::keepStrongerAssignment);
        }

        List<CandidateAssignment> ordered = strongestByBucket.values().stream()
                .sorted(Comparator
                        .comparingInt((CandidateAssignment item) -> rolePriority(item.assignment().role()))
                        .thenComparing(CandidateAssignment::strength, Comparator.reverseOrder())
                        .thenComparing(item -> item.assignment().bucketName(), Comparator.nullsLast(String::compareTo)))
                .toList();

        List<CandidateAssignment> selected = new ArrayList<>();
        boolean primaryUsed = false;
        int secondaryUsed = 0;
        for (CandidateAssignment candidate : ordered) {
            if (selected.size() >= MAX_BUCKETS_PER_EVIDENCE) {
                break;
            }
            CapabilityEvidenceRole role = roleOf(candidate.assignment());
            if (role == CapabilityEvidenceRole.PRIMARY && primaryUsed) {
                continue;
            }
            if (role == CapabilityEvidenceRole.SECONDARY && secondaryUsed >= 2) {
                continue;
            }
            selected.add(candidate);
            primaryUsed |= role == CapabilityEvidenceRole.PRIMARY;
            secondaryUsed += role == CapabilityEvidenceRole.SECONDARY ? 1 : 0;
        }

        AbilityFairnessPolicy.DimensionBudget budget = fairnessPolicy.normalizeDimensionBudget(
                selected.stream().map(CandidateAssignment::effectiveRelevance).toList()
        );
        List<PlannedBucket> planned = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            planned.add(new PlannedBucket(
                    selected.get(index).assignment(),
                    budget.adjustedRelevance().get(index),
                    budget.multiplier(),
                    budget.normalized()
            ));
        }
        return List.copyOf(planned);
    }

    private CandidateAssignment keepStrongerAssignment(CandidateAssignment left, CandidateAssignment right) {
        int compare = left.strength().compareTo(right.strength());
        if (compare != 0) {
            return compare >= 0 ? left : right;
        }
        return rolePriority(left.assignment().role()) <= rolePriority(right.assignment().role()) ? left : right;
    }

    private boolean isScoreableAssignment(CapabilityV2Dtos.BucketAssignment assignment) {
        return assignment != null
                && assignment.bucketId() != null
                && assignment.bucketType() != null
                && unit(assignment.relevance(), BigDecimal.ZERO).compareTo(MIN_RELEVANCE) >= 0;
    }

    private BigDecimal effectiveRelevance(CapabilityV2Dtos.BucketAssignment assignment) {
        BigDecimal roleMultiplier = roleOf(assignment) == CapabilityEvidenceRole.SECONDARY
                ? SECONDARY_ROLE_RELEVANCE_MULTIPLIER
                : BigDecimal.ONE;
        return unit(assignment.relevance(), BigDecimal.ZERO)
                .multiply(roleMultiplier)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal assessmentConfidence(ScoreSignals signals, CapabilityV2Dtos.BucketAssignment assignment) {
        return minimum(
                signals.assessmentConfidence(),
                signals.activityDifficultyConfidence(),
                signals.completionQualityConfidence(),
                assignment.relevanceConfidence()
        );
    }

    private UserCapabilityStateEntity createState(String userId, Long bucketId) {
        UserCapabilityStateEntity state = new UserCapabilityStateEntity();
        state.setUserId(userId);
        state.setBucketId(bucketId);
        state.setExperienceScore(BigDecimal.ZERO);
        state.setAbilityScore(BigDecimal.ZERO);
        state.setAbilityUncertainty(BigDecimal.ONE);
        state.setRankName("UNRATED");
        return state;
    }

    private void applyOutput(UserCapabilityStateEntity state, AbilityScoringModels.ScoreOutput output) {
        state.setExperienceScore(output.newExperienceScore());
        state.setAbilityScore(output.newAbilityScore());
        state.setAbilityUncertainty(output.newAbilityUncertainty());
        state.setRankName(rankPolicy.nextAppliedRank(state.getRankName(), output.proposedRank()));
        state.setEvidenceCount(state.getEvidenceCount() + 1);
    }

    private CapabilityEvidenceLinkEntity createEvidenceLink(
            ScoreRequest request,
            PlannedBucket planned,
            UserCapabilityStateEntity state,
            String idempotencyKey,
            CapabilityEvidenceLinkStatus status
    ) {
        CapabilityEvidenceLinkEntity link = new CapabilityEvidenceLinkEntity();
        link.setUserId(request.evidence().userId());
        link.setBucketId(planned.assignment().bucketId());
        link.setUserStateId(state.getId());
        link.setSourceType(request.evidence().sourceKind().name());
        link.setSourceId(request.evidence().sourceId());
        link.setEvidenceHash(request.evidence().evidenceHash());
        link.setLegacyAssessmentId(request.legacyAssessmentId());
        link.setRoleName(roleOf(planned.assignment()));
        link.setRelevance(planned.adjustedRelevance());
        link.setRelevanceConfidence(unit(planned.assignment().relevanceConfidence(), BigDecimal.ZERO));
        link.setClaimedOutcome(compact(planned.assignment().claimedOutcome(), 1200));
        link.setEvidenceFindingsJson(toJson(evidenceFindings(planned.assignment(), request.signals())));
        link.setStatus(status);
        link.setIdempotencyKey(idempotencyKey);
        link.setAgentRunId(request.agentRunId());
        return link;
    }

    private CapabilityScoreAuditEntity createAudit(
            ScoreRequest request,
            PlannedBucket planned,
            UserCapabilityStateEntity state,
            CapabilityEvidenceLinkEntity link,
            AbilityScoringModels.ScoreOutput output,
            CapabilityEvidenceLinkStatus status
    ) {
        Map<String, Object> factors = new LinkedHashMap<>(output.factorSnapshot());
        factors.put("capabilityV2EvidenceId", request.evidence().evidenceId());
        factors.put("capabilityV2EvidenceHash", request.evidence().evidenceHash());
        factors.put("bucketType", planned.assignment().bucketType().name());
        factors.put("bucketName", planned.assignment().bucketName());
        factors.put("role", roleOf(planned.assignment()).name());
        factors.put("originalRelevance", unit(planned.assignment().relevance(), BigDecimal.ZERO));
        factors.put("adjustedRelevance", planned.adjustedRelevance());
        factors.put("relevanceConfidence", unit(planned.assignment().relevanceConfidence(), BigDecimal.ZERO));
        factors.put("dimensionBudgetMultiplier", planned.budgetMultiplier());
        factors.put("dimensionBudgetNormalized", planned.budgetNormalized());
        factors.put("legacyAssessmentId", request.legacyAssessmentId());
        factors.put("assessmentRiskFlags", request.signals().riskFlags());
        factors.put("assignmentRiskFlags", safeStrings(planned.assignment().riskFlags(), 6, 160));
        factors.put("judgeFlags", output.judgeFlags());

        CapabilityScoreAuditEntity audit = new CapabilityScoreAuditEntity();
        audit.setUserId(request.evidence().userId());
        audit.setBucketId(planned.assignment().bucketId());
        audit.setUserStateId(state.getId());
        audit.setEvidenceLinkId(link.getId());
        audit.setLegacyScoreResultId(null);
        audit.setScoringRuleVersion(output.scoringRuleVersion());
        audit.setStatus(status.name());
        audit.setOldExperienceScore(output.oldExperienceScore());
        audit.setExperienceGain(output.verifiedExperienceGain());
        audit.setNewExperienceScore(output.newExperienceScore());
        audit.setOldAbilityScore(output.oldAbilityScore());
        audit.setNewAbilityScore(output.newAbilityScore());
        audit.setOldAbilityUncertainty(output.oldAbilityUncertainty());
        audit.setNewAbilityUncertainty(output.newAbilityUncertainty());
        audit.setFactorSnapshotJson(toJson(factors));
        audit.setAgentRunId(request.agentRunId());
        return audit;
    }

    private BucketScoreResult reusedResult(PlannedBucket planned, CapabilityEvidenceLinkEntity existingLink) {
        Optional<CapabilityScoreAuditEntity> audit = scoreAuditRepository
                .findByEvidenceLinkIdAndScoringRuleVersion(existingLink.getId(), AbilityScoringPolicyV2.RULE_VERSION);
        return new BucketScoreResult(
                planned.assignment().bucketId(),
                planned.assignment().bucketType(),
                planned.assignment().bucketName(),
                planned.assignment().role(),
                planned.assignment().relevance(),
                planned.adjustedRelevance(),
                planned.assignment().relevanceConfidence(),
                audit.map(CapabilityScoreAuditEntity::getExperienceGain).orElse(BigDecimal.ZERO),
                audit.map(CapabilityScoreAuditEntity::getNewAbilityScore).orElse(BigDecimal.ZERO),
                audit.map(CapabilityScoreAuditEntity::getNewAbilityUncertainty).orElse(BigDecimal.ONE),
                existingLink.getStatus(),
                List.of("IDEMPOTENT_REUSE"),
                true
        );
    }

    private BigDecimal profileConfidence(String userId) {
        return profileRepository.findById(userId)
                .map(profile -> unit(profile.getProfileConfidence(), DEFAULT_PROFILE_CONFIDENCE))
                .orElse(DEFAULT_PROFILE_CONFIDENCE);
    }

    private AbilityEvidenceSourceType sourceType(CapabilityEvidenceInput evidence) {
        try {
            return AbilityEvidenceSourceType.valueOf(safe(evidence.sourceType()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return switch (evidence.sourceKind()) {
                case ACTIVITY_COMPLETION -> AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD;
                case CHALLENGE_COMPLETION -> AbilityEvidenceSourceType.SELF_REPORT;
                case COACH_LOG -> AbilityEvidenceSourceType.PRIVATE_PERSONAL_RECORD;
                case RESUME_INTERVIEW -> AbilityEvidenceSourceType.RESUME_INTERVIEW;
            };
        }
    }

    private String bucketIdempotencyKey(CapabilityEvidenceInput evidence, Long bucketId) {
        // Do not include the mutable reflection hash here. A business completion has one
        // score lifecycle; edited reflections require a future correction/replay flow,
        // rather than silently adding a second full score.
        String material = String.join(":",
                "capability-v2",
                evidence.userId(),
                evidence.sourceKind().name(),
                evidence.sourceId(),
                evidence.evidenceId(),
                String.valueOf(bucketId),
                AbilityScoringPolicyV2.RULE_VERSION
        );
        return "capability-v2:" + sha256(material);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前环境不支持 SHA-256", exception);
        }
    }

    private List<String> evidenceFindings(CapabilityV2Dtos.BucketAssignment assignment, ScoreSignals signals) {
        LinkedHashSet<String> findings = new LinkedHashSet<>(safeStrings(assignment.evidenceFindings(), 6, 240));
        if (findings.isEmpty()) {
            findings.add("归因结果未提供细粒度事实点，按保守证据质量计分");
        }
        findings.addAll(safeStrings(signals.evidenceFindings(), 4, 240));
        return List.copyOf(findings);
    }

    private List<CapabilityV2Dtos.BucketAssignment> allAssignments(ScoreRequest request) {
        List<CapabilityV2Dtos.BucketAssignment> assignments = new ArrayList<>();
        if (request.knowledgeAssignments() != null) {
            assignments.addAll(request.knowledgeAssignments());
        }
        if (request.functionAssignments() != null) {
            assignments.addAll(request.functionAssignments());
        }
        return List.copyOf(assignments);
    }

    private void requireEligible(ScoreRequest request) {
        if (request == null || request.evidence() == null || request.signals() == null) {
            throw new IllegalArgumentException("v2 评分请求、证据和评估信号不能为空");
        }
        if (!request.evidence().scoringEligible()) {
            throw new IllegalArgumentException("该证据当前不可计分：" + request.evidence().eligibilityReason());
        }
    }

    private int rolePriority(CapabilityEvidenceRole role) {
        return switch (roleOf(role)) {
            case PRIMARY -> 0;
            case NONE -> 1;
            case SECONDARY -> 2;
        };
    }

    private CapabilityEvidenceRole roleOf(CapabilityV2Dtos.BucketAssignment assignment) {
        return roleOf(assignment == null ? null : assignment.role());
    }

    private CapabilityEvidenceRole roleOf(CapabilityEvidenceRole role) {
        return role == null ? CapabilityEvidenceRole.NONE : role;
    }

    private BigDecimal minimum(BigDecimal... values) {
        BigDecimal minimum = BigDecimal.ONE;
        for (BigDecimal value : values) {
            minimum = minimum.min(unit(value, BigDecimal.ZERO));
        }
        return minimum;
    }

    private BigDecimal unit(BigDecimal value, BigDecimal fallback) {
        BigDecimal actual = value == null ? fallback : value;
        return actual.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private List<String> safeStrings(List<String> values, int limit, int maxLength) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String compacted = compact(value, maxLength);
            if (!compacted.isBlank()) {
                result.add(compacted);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("双标签评分审计 JSON 序列化失败", exception);
        }
    }

    private String compact(String value, int maxLength) {
        String text = safe(value);
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record ScoreRequest(
            CapabilityEvidenceInput evidence,
            Long legacyAssessmentId,
            ScoreSignals signals,
            BigDecimal evidenceQuality,
            BigDecimal novelty,
            List<CapabilityV2Dtos.BucketAssignment> knowledgeAssignments,
            List<CapabilityV2Dtos.BucketAssignment> functionAssignments,
            Long agentRunId
    ) {
        public static ScoreRequest fromLegacyAssessment(
                CapabilityEvidenceInput evidence,
                AbilityEvidenceAssessmentEntity assessment,
                BigDecimal evidenceQuality,
                BigDecimal novelty,
                List<CapabilityV2Dtos.BucketAssignment> knowledgeAssignments,
                List<CapabilityV2Dtos.BucketAssignment> functionAssignments
        ) {
            if (assessment == null) {
                throw new IllegalArgumentException("能力证据评估不能为空");
            }
            return new ScoreRequest(
                    evidence,
                    assessment.getId(),
                    new ScoreSignals(
                            assessment.getActivityDifficulty(),
                            assessment.getActivityDifficultyConfidence(),
                            assessment.getCompletionQuality(),
                            assessment.getCompletionQualityConfidence(),
                            assessment.getPersonalContribution(),
                            assessment.getPersonalContributionConfidence(),
                            assessment.getAssessmentConfidence(),
                            List.of(),
                            List.of()
                    ),
                    evidenceQuality,
                    novelty,
                    knowledgeAssignments,
                    functionAssignments,
                    null
            );
        }
    }

    /** Numeric signals extracted by the Evidence Evaluator and validated before scoring. */
    public record ScoreSignals(
            BigDecimal activityDifficulty,
            BigDecimal activityDifficultyConfidence,
            BigDecimal completionQuality,
            BigDecimal completionQualityConfidence,
            BigDecimal personalContribution,
            BigDecimal personalContributionConfidence,
            BigDecimal assessmentConfidence,
            List<String> evidenceFindings,
            List<String> riskFlags
    ) {
        public ScoreSignals {
            activityDifficulty = activityDifficulty == null ? BigDecimal.ZERO : activityDifficulty;
            activityDifficultyConfidence = unitValue(activityDifficultyConfidence, BigDecimal.ZERO);
            completionQuality = unitValue(completionQuality, BigDecimal.ZERO);
            completionQualityConfidence = unitValue(completionQualityConfidence, BigDecimal.ZERO);
            personalContribution = unitValue(personalContribution, BigDecimal.ZERO);
            personalContributionConfidence = unitValue(personalContributionConfidence, BigDecimal.ZERO);
            assessmentConfidence = unitValue(assessmentConfidence, BigDecimal.ZERO);
            evidenceFindings = evidenceFindings == null ? List.of() : List.copyOf(evidenceFindings);
            riskFlags = riskFlags == null ? List.of() : List.copyOf(riskFlags);
        }

        private static BigDecimal unitValue(BigDecimal value, BigDecimal fallback) {
            BigDecimal actual = value == null ? fallback : value;
            return actual.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        }
    }

    public record ScoringOutcome(
            String evidenceId,
            int skippedAssignments,
            List<BucketScoreResult> bucketResults
    ) {
    }

    public record BucketScoreResult(
            Long bucketId,
            CapabilityBucketType bucketType,
            String bucketName,
            CapabilityEvidenceRole role,
            BigDecimal originalRelevance,
            BigDecimal adjustedRelevance,
            BigDecimal relevanceConfidence,
            BigDecimal experienceGain,
            BigDecimal newAbilityScore,
            BigDecimal newAbilityUncertainty,
            CapabilityEvidenceLinkStatus status,
            List<String> judgeFlags,
            boolean reusedExistingResult
    ) {
    }

    public record ScoringPreview(
            List<PreviewBucketResult> bucketResults,
            List<PlannedBucket> plannedBuckets,
            int skippedAssignments
    ) {
    }

    public record PreviewBucketResult(
            Long bucketId,
            CapabilityBucketType bucketType,
            String bucketName,
            CapabilityEvidenceRole role,
            BigDecimal originalRelevance,
            BigDecimal adjustedRelevance,
            BigDecimal relevanceConfidence,
            AbilityScoringModels.ScoreOutput scoreOutput
    ) {
    }

    private record CandidateAssignment(
            CapabilityV2Dtos.BucketAssignment assignment,
            BigDecimal effectiveRelevance
    ) {
        private BigDecimal strength() {
            return effectiveRelevance.multiply(unit(assignment.relevanceConfidence(), BigDecimal.ZERO));
        }

        private static BigDecimal unit(BigDecimal value, BigDecimal fallback) {
            BigDecimal actual = value == null ? fallback : value;
            return actual.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        }
    }

    public record PlannedBucket(
            CapabilityV2Dtos.BucketAssignment assignment,
            BigDecimal adjustedRelevance,
            BigDecimal budgetMultiplier,
            boolean budgetNormalized
    ) {
    }
}
