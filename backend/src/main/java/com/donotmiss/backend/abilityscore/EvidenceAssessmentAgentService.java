package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.achievement.AchievementSourceType;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.eventquality.EventQualityReportRepository;
import com.donotmiss.backend.eventquality.EventQualityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class EvidenceAssessmentAgentService {
    public static final String PROMPT_VERSION = "ability-evidence-prompt-v2.0";
    public static final String RUBRIC_VERSION = "irt-evidence-v2";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final AchievementRecordRepository recordRepository;
    private final EventQualityReportRepository qualityReportRepository;
    private final EventQualityService eventQualityService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final EvidenceAssessmentPersistenceService persistenceService;
    private final AbilityFairnessPolicy fairnessPolicy;
    private final AbilityEvidenceAssessmentRepository assessmentRepository;
    private final AbilityScoringService scoringService;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public EvidenceAssessmentAgentService(AchievementRecordRepository recordRepository,
                                          EventQualityReportRepository qualityReportRepository,
                                          EventQualityService eventQualityService,
                                          OpenAiCompatibleLlmClient llmClient,
                                          EvidenceAssessmentPersistenceService persistenceService,
                                          AbilityFairnessPolicy fairnessPolicy,
                                          AbilityEvidenceAssessmentRepository assessmentRepository,
                                          AbilityScoringService scoringService,
                                          AgentRunService agentRunService,
                                          ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.qualityReportRepository = qualityReportRepository;
        this.eventQualityService = eventQualityService;
        this.llmClient = llmClient;
        this.persistenceService = persistenceService;
        this.fairnessPolicy = fairnessPolicy;
        this.assessmentRepository = assessmentRepository;
        this.scoringService = scoringService;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    public EvidenceAssessmentDtos.AssessmentResponse assessRecord(Long recordId, String expectedUserId) {
        AchievementRecordEntity record = expectedUserId == null || expectedUserId.isBlank()
                ? recordRepository.findById(recordId)
                .orElseThrow(() -> ApiException.notFound("历史记录不存在：" + recordId))
                : recordRepository.findByIdAndUserId(recordId, expectedUserId)
                .orElseThrow(() -> ApiException.notFound("历史记录不存在：" + recordId));

        Long runId = agentRunService.startRun(
                record.getUserId(),
                AgentRunType.ABILITY_EVIDENCE_ASSESSMENT,
                "评估成长证据：" + record.getEventTitle(),
                "recordId=" + record.getId() + ", sourceType=" + record.getSourceType()
        );
        AbilityAssessmentJobEntity job = null;
        try {
            Long snapshotStep = agentRunService.startStep(
                    runId,
                    AgentStepName.EVIDENCE_SNAPSHOT,
                    "Build canonical evidence snapshot and SHA-256 hash."
            );
            Map<String, Object> snapshot = buildSnapshot(record);
            String snapshotJson = toJson(snapshot);
            String evidenceHash = sha256(snapshotJson);
            String contentFingerprint = sha256(toJson(buildFingerprintSnapshot(record)));
            job = persistenceService.findOrCreateJob(
                    record.getUserId(),
                    record.getId(),
                    evidenceHash,
                    contentFingerprint,
                    PROMPT_VERSION,
                    RUBRIC_VERSION,
                    snapshotJson
            );
            agentRunService.completeStep(
                    snapshotStep,
                    "jobId=" + job.getId() + ", evidenceHash=" + evidenceHash
            );

            if (job.getFairnessStatus() == AbilityAssessmentFairnessStatus.DUPLICATE_REVIEW) {
                agentRunService.finishRun(
                        runId,
                        "duplicateEvidence=true, duplicateOfJobId=" + job.getDuplicateOfJobId()
                );
                return new EvidenceAssessmentDtos.AssessmentResponse(
                        job.getId(),
                        null,
                        job.getRequestId(),
                        record.getUserId(),
                        record.getId(),
                        job.getStatus().name(),
                        job.getFairnessStatus().name(),
                        job.getDuplicateOfJobId(),
                        "duplicate-blocked",
                        job.getEvidenceHash(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new AbilityScoringExecution(null, job.getRequestId(), 0, 0, List.of())
                );
            }

            Optional<AbilityEvidenceAssessmentEntity> existingAssessment =
                    assessmentRepository.findByJobId(job.getId());
            EvidenceAssessmentMaterialBundle bundle;
            if (existingAssessment.isPresent()
                    && job.getStatus() != AbilityAssessmentJobStatus.FAILED
                    && job.getStatus() != AbilityAssessmentJobStatus.PENDING) {
                bundle = fromStoredAssessment(existingAssessment.get(), record);
            } else {
                persistenceService.markRunning(job.getId(), llmClient.modeLabel());
                bundle = extractAndValidate(record, snapshot, evidenceHash, runId, job.getId());
                AbilityEvidenceAssessmentEntity saved = persistenceService.saveAssessment(
                        job.getId(),
                        bundle.material()
                );
                bundle = new EvidenceAssessmentMaterialBundle(
                        saved,
                        bundle.sourceType(),
                        bundle.evidenceQuality(),
                        bundle.novelty(),
                        bundle.mode()
                );
            }

            Long scoringStep = agentRunService.startStep(
                    runId,
                    AgentStepName.DETERMINISTIC_SCORING,
                    "Run versioned Java scoring engine without LLM calculation."
            );
            AbilityScoringExecution scoring = scoringService.score(new AbilityScoringCommand(
                    bundle.assessment().getId(),
                    bundle.sourceType(),
                    bundle.evidenceQuality(),
                    bundle.novelty(),
                    historySnapshotVersion(record)
            ));
            agentRunService.completeStep(
                    scoringStep,
                    "dimensions=" + scoring.scoredDimensions() + ", skipped=" + scoring.skippedDimensions()
            );
            agentRunService.finishRun(
                    runId,
                    "assessmentId=" + bundle.assessment().getId() + ", mode=" + bundle.mode()
            );

            AbilityAssessmentJobEntity completedJob = persistenceService.getJob(job.getId());
            return new EvidenceAssessmentDtos.AssessmentResponse(
                    completedJob.getId(),
                    bundle.assessment().getId(),
                    completedJob.getRequestId(),
                    record.getUserId(),
                    record.getId(),
                    completedJob.getStatus().name(),
                    completedJob.getFairnessStatus().name(),
                    completedJob.getDuplicateOfJobId(),
                    bundle.mode(),
                    completedJob.getEvidenceHash(),
                    bundle.evidenceQuality(),
                    bundle.novelty(),
                    scoring
            );
        } catch (RuntimeException | Error ex) {
            if (job != null) {
                persistenceService.markFailed(job.getId(), ex);
            }
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private EvidenceAssessmentMaterialBundle extractAndValidate(AchievementRecordEntity record,
                                                                 Map<String, Object> snapshot,
                                                                 String evidenceHash,
                                                                 Long runId,
                                                                 Long jobId) {
        Long extractionStep = agentRunService.startStep(
                runId,
                AgentStepName.EVIDENCE_EXTRACTION,
                "Extract difficulty, completion, contribution, evidence and dimensions."
        );

        Optional<EvidenceAssessmentDtos.ModelResponse> modelResponse = llmClient.isEnabled()
                ? llmClient.chatForJson(systemPrompt(), toJson(snapshot), EvidenceAssessmentDtos.ModelResponse.class)
                : Optional.empty();
        String mode = modelResponse.isPresent() ? llmClient.modeLabel() : "rule";
        EvidenceAssessmentDtos.ModelResponse raw = modelResponse.orElseGet(() -> localFallback(record));
        agentRunService.completeStep(extractionStep, "mode=" + mode);

        Long validationStep = agentRunService.startStep(
                runId,
                AgentStepName.EVIDENCE_VALIDATION,
                "Clamp values, normalize dimensions and calculate server-owned evidence signals."
        );
        EvidenceAssessmentDtos.ModelResponse sanitized = sanitize(raw, record);
        if (sanitized.riskFlags().contains("FAIRNESS_CONTRIBUTION_CAPPED")) {
            persistenceService.markFairnessStatus(
                    jobId,
                    AbilityAssessmentFairnessStatus.CONTRIBUTION_CAPPED
            );
        }
        AbilityEvidenceSourceType sourceType = sourceType(record);
        BigDecimal localEvidenceQuality = localEvidenceQuality(record);
        BigDecimal modelEvidenceQuality = modelEvidenceQuality(sanitized.evidenceFindings());
        BigDecimal evidenceQuality = scaled(
                localEvidenceQuality.multiply(new BigDecimal("0.60"))
                        .add(modelEvidenceQuality.multiply(new BigDecimal("0.40")))
        );
        BigDecimal novelty = calculateNovelty(record);
        var material = toMaterial(
                sanitized,
                evidenceHash,
                mode,
                sourceType,
                evidenceQuality,
                novelty
        );
        agentRunService.completeStep(
                validationStep,
                "source=" + sourceType + ", evidenceQuality=" + evidenceQuality
                        + ", novelty=" + novelty + ", dimensions=" + material.dimensions().size()
        );
        return new EvidenceAssessmentMaterialBundle(
                null,
                material,
                sourceType,
                evidenceQuality,
                novelty,
                mode
        );
    }

    private EvidenceAssessmentMaterialBundle fromStoredAssessment(AbilityEvidenceAssessmentEntity assessment,
                                                                   AchievementRecordEntity record) {
        List<EvidenceAssessmentDtos.EvidenceFinding> findings = readFindings(assessment.getEvidenceFindingsJson());
        BigDecimal evidenceQuality = scaled(
                localEvidenceQuality(record).multiply(new BigDecimal("0.60"))
                        .add(modelEvidenceQuality(findings).multiply(new BigDecimal("0.40")))
        );
        return new EvidenceAssessmentMaterialBundle(
                assessment,
                sourceType(record),
                evidenceQuality,
                calculateNovelty(record),
                assessment.getModelName()
        );
    }

    private Map<String, Object> buildSnapshot(AchievementRecordEntity record) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recordId", record.getId());
        snapshot.put("userId", record.getUserId());
        snapshot.put("sourceType", record.getSourceType() == null ? null : record.getSourceType().name());
        snapshot.put("sourceId", record.getSourceId());
        snapshot.put("title", record.getEventTitle());
        snapshot.put("organizationName", record.getOrganizationName());
        snapshot.put("category", record.getCategory());
        snapshot.put("eventStartTime", record.getEventStartTime());
        snapshot.put("location", record.getLocation());
        snapshot.put("content", record.getContent());
        snapshot.put("skill", record.getSkill());
        snapshot.put("completedAt", record.getCompletedAt());
        snapshot.put("did", record.getDid());
        snapshot.put("learned", record.getLearned());
        snapshot.put("serverSourceType", sourceType(record).name());

        if (record.getSourceType() == AchievementSourceType.EVENT && record.getEventId() != null) {
            qualityReportRepository.findByEventId(record.getEventId())
                    .map(eventQualityService::toResponse)
                    .ifPresent(report -> snapshot.put("eventQualityReport", report));
        }
        return snapshot;
    }

    private Map<String, Object> buildFingerprintSnapshot(AchievementRecordEntity record) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("sourceType", record.getSourceType() == null ? null : record.getSourceType().name());
        fingerprint.put("title", normalizeForFingerprint(record.getEventTitle()));
        fingerprint.put("organizationName", normalizeForFingerprint(record.getOrganizationName()));
        fingerprint.put("category", normalizeForFingerprint(record.getCategory()));
        fingerprint.put("content", normalizeForFingerprint(record.getContent()));
        fingerprint.put("skill", normalizeForFingerprint(record.getSkill()));
        fingerprint.put("did", normalizeForFingerprint(record.getDid()));
        fingerprint.put("learned", normalizeForFingerprint(record.getLearned()));
        return fingerprint;
    }

    private EvidenceAssessmentDtos.ModelResponse sanitize(EvidenceAssessmentDtos.ModelResponse response,
                                                           AchievementRecordEntity record) {
        EvidenceAssessmentDtos.ModelResponse fallback = localFallback(record);
        List<EvidenceAssessmentDtos.DimensionItem> dimensions = sanitizeDimensions(response.dimensions());
        if (dimensions.isEmpty()) {
            dimensions = fallback.dimensions();
        }
        List<EvidenceAssessmentDtos.EvidenceFinding> findings =
                sanitizeFindings(response.evidenceFindings());
        if (findings.isEmpty()) {
            findings = fallback.evidenceFindings();
        }
        AbilityFairnessPolicy.ContributionDecision contributionDecision =
                fairnessPolicy.adjustTeamContribution(
                        recordText(record),
                        record.getDid(),
                        unit(response.personalContribution(), fallback.personalContribution()),
                        unit(response.personalContributionConfidence(), fallback.personalContributionConfidence())
                );
        List<String> riskFlags = new ArrayList<>(sanitizeStrings(response.riskFlags(), 10, 160));
        if (contributionDecision.capped()) {
            riskFlags.add("FAIRNESS_CONTRIBUTION_CAPPED");
            riskFlags.addAll(contributionDecision.reasons());
        }
        return new EvidenceAssessmentDtos.ModelResponse(
                compact(firstPresent(response.normalizedActivityType(), fallback.normalizedActivityType()), 48),
                range(response.activityDifficulty(), fallback.activityDifficulty(), ZERO, new BigDecimal("100")),
                unit(response.activityDifficultyConfidence(), fallback.activityDifficultyConfidence()),
                unit(response.completionQuality(), fallback.completionQuality()),
                unit(response.completionQualityConfidence(), fallback.completionQualityConfidence()),
                contributionDecision.personalContribution(),
                contributionDecision.personalContributionConfidence(),
                unit(response.assessmentConfidence(), fallback.assessmentConfidence()),
                dimensions,
                findings,
                sanitizeNoveltyFeatures(response.noveltyFeatures(), fallback.noveltyFeatures()),
                List.copyOf(new LinkedHashSet<>(riskFlags)),
                sanitizeJudgeRecommendation(response.judgeRecommendation())
        );
    }

    private EvidenceAssessmentDtos.ModelResponse localFallback(AchievementRecordEntity record) {
        BigDecimal difficulty = fallbackDifficulty(record);
        boolean hasDid = hasText(record.getDid());
        boolean hasLearned = hasText(record.getLearned());
        BigDecimal completion = new BigDecimal("0.70")
                .add(hasDid ? new BigDecimal("0.10") : ZERO)
                .add(hasLearned ? new BigDecimal("0.10") : ZERO)
                .min(ONE);
        BigDecimal contribution = record.getSourceType() == AchievementSourceType.CHALLENGE
                ? new BigDecimal("0.95")
                : hasDid ? new BigDecimal("0.75") : new BigDecimal("0.60");
        BigDecimal contributionConfidence = hasDid
                ? new BigDecimal("0.75")
                : new BigDecimal("0.55");

        List<EvidenceAssessmentDtos.DimensionItem> dimensions = fallbackDimensions(record);
        List<EvidenceAssessmentDtos.EvidenceFinding> findings = new ArrayList<>();
        findings.add(new EvidenceAssessmentDtos.EvidenceFinding(
                "completion-record-" + record.getId(),
                "completion_record",
                record.getSourceType() == AchievementSourceType.EVENT
                        ? new BigDecimal("0.72") : new BigDecimal("0.42"),
                record.getSourceType() == AchievementSourceType.EVENT
                        ? "third_party_record" : "self_report",
                List.of("completion")
        ));
        if (hasDid || hasLearned) {
            findings.add(new EvidenceAssessmentDtos.EvidenceFinding(
                    "reflection-" + record.getId(),
                    "reflection",
                    new BigDecimal("0.55"),
                    "user_authored",
                    List.of("completion_quality", "personal_contribution")
            ));
        }
        return new EvidenceAssessmentDtos.ModelResponse(
                record.getSourceType() == AchievementSourceType.CHALLENGE ? "self_report" : "activity",
                difficulty,
                new BigDecimal("0.62"),
                completion,
                new BigDecimal("0.68"),
                contribution,
                contributionConfidence,
                new BigDecimal("0.60"),
                dimensions,
                findings,
                new EvidenceAssessmentDtos.NoveltyFeatures(
                        normalize(record.getCategory() + "-" + record.getSkill()),
                        splitTerms(record.getSkill()),
                        difficultyBand(difficulty)
                ),
                List.of("本地规则兜底，建议后续由模型或人工复核"),
                new EvidenceAssessmentDtos.JudgeRecommendation(false, List.of())
        );
    }

    private List<EvidenceAssessmentDtos.DimensionItem> fallbackDimensions(AchievementRecordEntity record) {
        Map<String, EvidenceAssessmentDtos.DimensionItem> dimensions = new LinkedHashMap<>();
        if (record.getSourceType() == AchievementSourceType.EVENT && record.getEventId() != null) {
            qualityReportRepository.findByEventId(record.getEventId()).ifPresent(report -> {
                eventQualityService.readAbilityImpacts(report.getAbilityImpactsJson()).forEach(impact -> {
                    if (impact != null && hasText(impact.tag())) {
                        String normalized = normalize(impact.tag());
                        BigDecimal relevance = impact.score() == null
                                ? new BigDecimal("0.55")
                                : new BigDecimal(impact.score())
                                .divide(new BigDecimal("30"), 4, RoundingMode.HALF_UP)
                                .max(new BigDecimal("0.30")).min(ONE);
                        BigDecimal confidence = impact.confidence() == null
                                ? new BigDecimal("0.55")
                                : BigDecimal.valueOf(impact.confidence()).max(ZERO).min(ONE);
                        dimensions.putIfAbsent(normalized, new EvidenceAssessmentDtos.DimensionItem(
                                impact.tag(),
                                normalized,
                                relevance,
                                confidence,
                                impact.evidence(),
                                List.of("event-quality-report")
                        ));
                    }
                });
            });
        }

        String text = recordText(record).toLowerCase(Locale.ROOT);
        addDimensionIfMatched(dimensions, text, "Java 后端开发", "java-backend", "完成 Java/后端相关实践",
                "java", "spring", "后端", "程序设计");
        addDimensionIfMatched(dimensions, text, "AI Agent 学习", "ai-agent", "完成 AI/Agent 相关实践",
                "agent", "llm", "rag", "大模型", "人工智能");
        addDimensionIfMatched(dimensions, text, "日语沟通", "japanese-communication", "完成日语相关实践",
                "日语", "日本语", "n5", "n4", "n3", "口语");
        addDimensionIfMatched(dimensions, text, "调研分析", "research-analysis", "完成调研或数据分析实践",
                "调研", "问卷", "访谈", "研究", "数据");
        addDimensionIfMatched(dimensions, text, "团队协作", "team-collaboration", "完成团队合作实践",
                "团队", "协作", "合作", "沟通");
        if (dimensions.isEmpty()) {
            String name = firstPresent(record.getSkill(), record.getCategory(), "综合实践");
            dimensions.put(normalize(name), new EvidenceAssessmentDtos.DimensionItem(
                    compact(name, 100),
                    normalize(name),
                    new BigDecimal("0.65"),
                    new BigDecimal("0.55"),
                    "根据活动技能和类别保守归纳",
                    List.of("achievement-record")
            ));
        }
        return dimensions.values().stream().limit(5).toList();
    }

    private EvidenceAssessmentPersistenceService.EvidenceAssessmentMaterial toMaterial(
            EvidenceAssessmentDtos.ModelResponse response,
            String evidenceHash,
            String mode,
            AbilityEvidenceSourceType sourceType,
            BigDecimal evidenceQuality,
            BigDecimal novelty) {
        List<EvidenceAssessmentPersistenceService.EvidenceAssessmentDimensionMaterial> dimensions =
                response.dimensions().stream()
                        .map(item -> new EvidenceAssessmentPersistenceService.EvidenceAssessmentDimensionMaterial(
                                item.dimension(),
                                item.normalizedDimension(),
                                item.relevance(),
                                item.confidence(),
                                compact(item.claimedOutcome(), 600),
                                toJson(item.evidenceRefs())
                        ))
                        .toList();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("modelResponse", response);
        raw.put("serverSignals", Map.of(
                "sourceType", sourceType.name(),
                "sourceCredibilityPrior", sourceType.credibilityPrior(),
                "evidenceQuality", evidenceQuality,
                "novelty", novelty
        ));

        return new EvidenceAssessmentPersistenceService.EvidenceAssessmentMaterial(
                response.normalizedActivityType(),
                response.activityDifficulty(),
                response.activityDifficultyConfidence(),
                response.completionQuality(),
                response.completionQualityConfidence(),
                response.personalContribution(),
                response.personalContributionConfidence(),
                response.assessmentConfidence(),
                toJson(response.evidenceFindings()),
                toJson(response.noveltyFeatures()),
                toJson(response.riskFlags()),
                toJson(response.judgeRecommendation()),
                toJson(raw),
                evidenceHash,
                PROMPT_VERSION,
                RUBRIC_VERSION,
                mode,
                dimensions
        );
    }

    private AbilityEvidenceSourceType sourceType(AchievementRecordEntity record) {
        if (record.getSourceType() == AchievementSourceType.CHALLENGE) {
            return AbilityEvidenceSourceType.SELF_REPORT;
        }
        String category = nullToEmpty(record.getCategory()).toLowerCase(Locale.ROOT);
        String organization = nullToEmpty(record.getOrganizationName()).toLowerCase(Locale.ROOT);
        if (category.contains("企业") || containsAny(organization, "公司", "株式会社", "inc", "ltd")) {
            return AbilityEvidenceSourceType.COMPANY_INTERNSHIP_OR_WORK;
        }
        if (category.contains("校内") || category.contains("研究")
                || containsAny(organization, "大学", "学院", "研究室", "lab")) {
            return AbilityEvidenceSourceType.SCHOOL_COURSE_OR_ASSIGNMENT;
        }
        return AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD;
    }

    private BigDecimal localEvidenceQuality(AchievementRecordEntity record) {
        BigDecimal quality = record.getSourceType() == AchievementSourceType.EVENT
                ? new BigDecimal("0.62") : new BigDecimal("0.35");
        if (hasText(record.getDid())) {
            quality = quality.add(new BigDecimal("0.08"));
        }
        if (hasText(record.getLearned())) {
            quality = quality.add(new BigDecimal("0.08"));
        }
        if (record.getSourceType() == AchievementSourceType.EVENT && record.getEventId() != null) {
            var report = qualityReportRepository.findByEventId(record.getEventId()).orElse(null);
            if (report != null) {
                quality = quality.add(
                        BigDecimal.valueOf(report.getConfidence()).multiply(new BigDecimal("0.08"))
                );
            }
        }
        return quality.min(new BigDecimal("0.85"));
    }

    private BigDecimal modelEvidenceQuality(List<EvidenceAssessmentDtos.EvidenceFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return new BigDecimal("0.40");
        }
        return findings.stream()
                .map(EvidenceAssessmentDtos.EvidenceFinding::quality)
                .filter(value -> value != null)
                .map(value -> value.max(ZERO).min(ONE))
                .max(BigDecimal::compareTo)
                .orElse(new BigDecimal("0.40"));
    }

    private BigDecimal calculateNovelty(AchievementRecordEntity current) {
        LinkedHashSet<String> currentTerms = meaningfulTerms(recordText(current));
        double maxSimilarity = 0.0;
        for (AchievementRecordEntity previous : recordRepository.findByUserIdOrderByCompletedAtDesc(current.getUserId())) {
            if (previous.getId().equals(current.getId())) {
                continue;
            }
            LinkedHashSet<String> previousTerms = meaningfulTerms(recordText(previous));
            maxSimilarity = Math.max(maxSimilarity, jaccard(currentTerms, previousTerms));
        }
        double novelty = Math.max(0.10, 1.0 - maxSimilarity);
        return BigDecimal.valueOf(novelty).setScale(4, RoundingMode.HALF_UP);
    }

    private String historySnapshotVersion(AchievementRecordEntity record) {
        long count = recordRepository.findByUserIdOrderByCompletedAtDesc(record.getUserId()).size();
        return "history-count-" + count;
    }

    private List<EvidenceAssessmentDtos.DimensionItem> sanitizeDimensions(
            List<EvidenceAssessmentDtos.DimensionItem> values) {
        if (values == null) {
            return List.of();
        }
        Map<String, EvidenceAssessmentDtos.DimensionItem> cleaned = new LinkedHashMap<>();
        for (EvidenceAssessmentDtos.DimensionItem value : values) {
            if (value == null || !hasText(value.dimension())) {
                continue;
            }
            String normalized = normalize(firstPresent(value.normalizedDimension(), value.dimension()));
            cleaned.putIfAbsent(normalized, new EvidenceAssessmentDtos.DimensionItem(
                    compact(value.dimension(), 100),
                    compact(normalized, 100),
                    unit(value.relevance(), new BigDecimal("0.50")),
                    unit(value.confidence(), new BigDecimal("0.55")),
                    compact(value.claimedOutcome(), 600),
                    sanitizeStrings(value.evidenceRefs(), 10, 100)
            ));
            if (cleaned.size() >= 5) {
                break;
            }
        }
        return List.copyOf(cleaned.values());
    }

    private List<EvidenceAssessmentDtos.EvidenceFinding> sanitizeFindings(
            List<EvidenceAssessmentDtos.EvidenceFinding> values) {
        if (values == null) {
            return List.of();
        }
        List<EvidenceAssessmentDtos.EvidenceFinding> cleaned = new ArrayList<>();
        for (EvidenceAssessmentDtos.EvidenceFinding value : values) {
            if (value == null || !hasText(value.evidenceType())) {
                continue;
            }
            cleaned.add(new EvidenceAssessmentDtos.EvidenceFinding(
                    compact(firstPresent(value.evidenceId(), "evidence-" + (cleaned.size() + 1)), 100),
                    compact(value.evidenceType(), 60),
                    unit(value.quality(), new BigDecimal("0.40")),
                    compact(firstPresent(value.verificationStatus(), "unknown"), 60),
                    sanitizeStrings(value.supports(), 10, 80)
            ));
            if (cleaned.size() >= 10) {
                break;
            }
        }
        return List.copyOf(cleaned);
    }

    private EvidenceAssessmentDtos.NoveltyFeatures sanitizeNoveltyFeatures(
            EvidenceAssessmentDtos.NoveltyFeatures value,
            EvidenceAssessmentDtos.NoveltyFeatures fallback) {
        if (value == null) {
            return fallback;
        }
        return new EvidenceAssessmentDtos.NoveltyFeatures(
                compact(firstPresent(value.activityFamily(), fallback.activityFamily()), 100),
                sanitizeStrings(value.techniques(), 12, 80),
                compact(firstPresent(value.difficultyBand(), fallback.difficultyBand()), 40)
        );
    }

    private EvidenceAssessmentDtos.JudgeRecommendation sanitizeJudgeRecommendation(
            EvidenceAssessmentDtos.JudgeRecommendation value) {
        if (value == null) {
            return new EvidenceAssessmentDtos.JudgeRecommendation(false, List.of());
        }
        return new EvidenceAssessmentDtos.JudgeRecommendation(
                Boolean.TRUE.equals(value.suggestReview()),
                sanitizeStrings(value.reasons(), 10, 160)
        );
    }

    private List<EvidenceAssessmentDtos.EvidenceFinding> readFindings(String json) {
        try {
            var type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, EvidenceAssessmentDtos.EvidenceFinding.class);
            return objectMapper.readValue(json == null ? "[]" : json, type);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private BigDecimal fallbackDifficulty(AchievementRecordEntity record) {
        if (record.getSourceType() == AchievementSourceType.EVENT && record.getEventId() != null) {
            Optional<String> difficulty = qualityReportRepository.findByEventId(record.getEventId())
                    .map(report -> report.getDifficulty().toLowerCase(Locale.ROOT));
            if (difficulty.isPresent()) {
                return switch (difficulty.get()) {
                    case "zero" -> new BigDecimal("20");
                    case "basic" -> new BigDecimal("35");
                    case "intermediate" -> new BigDecimal("60");
                    case "advanced" -> new BigDecimal("80");
                    default -> new BigDecimal("45");
                };
            }
        }
        String text = recordText(record).toLowerCase(Locale.ROOT);
        if (containsAny(text, "获奖", "上线", "论文", "竞赛", "完整项目", "高级")) {
            return new BigDecimal("70");
        }
        if (containsAny(text, "项目", "开发", "研究", "考试", "证书")) {
            return new BigDecimal("55");
        }
        if (containsAny(text, "零基础", "入门", "初学", "基础")) {
            return new BigDecimal("30");
        }
        return new BigDecimal("45");
    }

    private String systemPrompt() {
        return """
                你是 Do Not Miss 的 Evidence Assessment Agent。
                你负责把一条已经完成的活动或挑战整理成结构化证据，供后端 Java 评分引擎使用。
                你不是最终裁判，禁止返回 scoreDelta、最终得分、等级晋升或 profileConfidence 变化。
                只能依据输入中的活动事实、活动质量报告、用户 did/learned 复盘进行判断，不得编造证书、代码、排名或第三方验证。

                必须只返回 JSON 对象：
                {
                  "normalizedActivityType": "project|contest|course|exam|practice|work|certificate|self_report|other",
                  "activityDifficulty": 0-100,
                  "activityDifficultyConfidence": 0.0-1.0,
                  "completionQuality": 0.0-1.0,
                  "completionQualityConfidence": 0.0-1.0,
                  "personalContribution": 0.0-1.0,
                  "personalContributionConfidence": 0.0-1.0,
                  "assessmentConfidence": 0.0-1.0,
                  "dimensions": [{
                    "dimension": "能力名称",
                    "normalizedDimension": "稳定的小写标识",
                    "relevance": 0.0-1.0,
                    "confidence": 0.0-1.0,
                    "claimedOutcome": "这项经历实际支持的能力结果",
                    "evidenceRefs": ["输入证据引用"]
                  }],
                  "evidenceFindings": [{
                    "evidenceId": "稳定标识",
                    "evidenceType": "completion_record|reflection|event_quality_report|other",
                    "quality": 0.0-1.0,
                    "verificationStatus": "self_report|third_party_record|partially_verified|unknown",
                    "supports": ["completion_quality","personal_contribution","dimension"]
                  }],
                  "noveltyFeatures": {
                    "activityFamily": "活动族",
                    "techniques": ["实际涉及的技术或能力动作"],
                    "difficultyBand": "basic|intermediate|advanced|unknown"
                  },
                  "riskFlags": ["不确定项"],
                  "judgeRecommendation": {
                    "suggestReview": false,
                    "reasons": []
                  }
                }

                判断要求：
                1. 参加活动不等于高质量完成；结合 did、learned 和可验证记录判断。
                2. 团队活动如果个人贡献不明确，personalContribution 和 confidence 必须保守。
                3. 活动质量报告描述的是活动理论价值，不能直接证明学生已经掌握。
                4. dimensions 最多 5 个，只保留真实相关能力。
                5. normalizedDimension 要稳定，例如 java-backend、redis、japanese-communication。
                """;
    }

    private void addDimensionIfMatched(Map<String, EvidenceAssessmentDtos.DimensionItem> target,
                                       String text,
                                       String name,
                                       String normalized,
                                       String outcome,
                                       String... keywords) {
        if (containsAny(text, keywords)) {
            target.putIfAbsent(normalized, new EvidenceAssessmentDtos.DimensionItem(
                    name,
                    normalized,
                    new BigDecimal("0.78"),
                    new BigDecimal("0.65"),
                    outcome,
                    List.of("achievement-record")
            ));
        }
    }

    private LinkedHashSet<String> meaningfulTerms(String text) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String value : nullToEmpty(text).toLowerCase(Locale.ROOT)
                .split("[\\s,，。；;:：!！?？()（）\\[\\]【】、/\\\\]+")) {
            String term = value.trim();
            if (term.length() >= 2 && !List.of(
                    "活动", "学习", "项目", "完成", "相关", "能力", "经验", "参加", "进行"
            ).contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private double jaccard(LinkedHashSet<String> left, LinkedHashSet<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        LinkedHashSet<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        LinkedHashSet<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private String recordText(AchievementRecordEntity record) {
        return String.join(" ",
                nullToEmpty(record.getEventTitle()),
                nullToEmpty(record.getOrganizationName()),
                nullToEmpty(record.getCategory()),
                nullToEmpty(record.getContent()),
                nullToEmpty(record.getSkill()),
                nullToEmpty(record.getDid()),
                nullToEmpty(record.getLearned())
        );
    }

    private String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String difficultyBand(BigDecimal difficulty) {
        if (difficulty.compareTo(new BigDecimal("40")) < 0) {
            return "basic";
        }
        if (difficulty.compareTo(new BigDecimal("70")) < 0) {
            return "intermediate";
        }
        return "advanced";
    }

    private List<String> splitTerms(String value) {
        return sanitizeStrings(
                hasText(value) ? List.of(value.split("[,，、;；\\s]+")) : List.of(),
                10,
                80
        );
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> sanitizeStrings(List<String> values, int maxSize, int maxLength) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) {
                cleaned.add(compact(value, maxLength));
            }
            if (cleaned.size() >= maxSize) {
                break;
            }
        }
        return List.copyOf(cleaned);
    }

    private BigDecimal unit(BigDecimal value, BigDecimal fallback) {
        return range(value, fallback, ZERO, ONE);
    }

    private BigDecimal range(BigDecimal value, BigDecimal fallback, BigDecimal min, BigDecimal max) {
        BigDecimal actual = value == null ? fallback : value;
        return actual.max(min).min(max).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private String normalize(String value) {
        String normalized = nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[\\s_]+", "-");
        normalized = normalized.replaceAll("[^a-z0-9\\-\\p{IsHan}]", "");
        return compact(normalized.isBlank() ? "general-practice" : normalized, 100);
    }

    private String normalizeForFingerprint(String value) {
        return nullToEmpty(value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[，。；：！？、,.!?;:()（）\\[\\]【】]", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String compact(String value, int maxLength) {
        String text = nullToEmpty(value).trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("证据快照 JSON 序列化失败", ex);
        }
    }

    private record EvidenceAssessmentMaterialBundle(
            AbilityEvidenceAssessmentEntity assessment,
            EvidenceAssessmentPersistenceService.EvidenceAssessmentMaterial material,
            AbilityEvidenceSourceType sourceType,
            BigDecimal evidenceQuality,
            BigDecimal novelty,
            String mode
    ) {
        private EvidenceAssessmentMaterialBundle(AbilityEvidenceAssessmentEntity assessment,
                                                 AbilityEvidenceSourceType sourceType,
                                                 BigDecimal evidenceQuality,
                                                 BigDecimal novelty,
                                                 String mode) {
            this(assessment, null, sourceType, evidenceQuality, novelty, mode);
        }
    }
}
