package com.donotmiss.backend.abilityscore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class EvidenceAssessmentPersistenceService {
    private final AbilityAssessmentJobRepository jobRepository;
    private final AbilityEvidenceAssessmentRepository assessmentRepository;
    private final AbilityEvidenceDimensionRepository dimensionRepository;

    public EvidenceAssessmentPersistenceService(AbilityAssessmentJobRepository jobRepository,
                                                AbilityEvidenceAssessmentRepository assessmentRepository,
                                                AbilityEvidenceDimensionRepository dimensionRepository) {
        this.jobRepository = jobRepository;
        this.assessmentRepository = assessmentRepository;
        this.dimensionRepository = dimensionRepository;
    }

    @Transactional
    public AbilityAssessmentJobEntity findOrCreateJob(String userId,
                                                      Long achievementRecordId,
                                                      String evidenceHash,
                                                      String contentFingerprint,
                                                      String promptVersion,
                                                      String rubricVersion,
                                                      String inputSnapshotJson) {
        Optional<AbilityAssessmentJobEntity> existing =
                jobRepository.findByAchievementRecordIdAndEvidenceHashAndPromptVersionAndRubricVersion(
                        achievementRecordId,
                        evidenceHash,
                        promptVersion,
                        rubricVersion
        );
        if (existing.isPresent()) {
            AbilityAssessmentJobEntity job = existing.get();
            if (job.getContentFingerprint() == null || job.getContentFingerprint().isBlank()) {
                job.setContentFingerprint(contentFingerprint);
                jobRepository.findFirstByUserIdAndContentFingerprintAndAchievementRecordIdNotOrderByCreatedAtDesc(
                        userId,
                        contentFingerprint,
                        achievementRecordId
                ).ifPresent(duplicate -> {
                    job.setFairnessStatus(AbilityAssessmentFairnessStatus.DUPLICATE_REVIEW);
                    job.setDuplicateOfJobId(duplicate.getId());
                    job.setStatus(AbilityAssessmentJobStatus.REVIEW_REQUIRED);
                });
                jobRepository.save(job);
            }
            if (job.getStatus() == AbilityAssessmentJobStatus.FAILED) {
                job.setStatus(AbilityAssessmentJobStatus.PENDING);
                job.setErrorMessage(null);
            }
            return job;
        }

        AbilityAssessmentJobEntity job = new AbilityAssessmentJobEntity();
        job.setRequestId(UUID.randomUUID().toString());
        job.setUserId(userId);
        job.setAchievementRecordId(achievementRecordId);
        job.setStatus(AbilityAssessmentJobStatus.PENDING);
        job.setFairnessStatus(AbilityAssessmentFairnessStatus.CLEAR);
        job.setEvidenceHash(evidenceHash);
        job.setContentFingerprint(contentFingerprint);
        job.setPromptVersion(promptVersion);
        job.setRubricVersion(rubricVersion);
        job.setInputSnapshotJson(inputSnapshotJson);
        jobRepository.findFirstByUserIdAndContentFingerprintAndAchievementRecordIdNotOrderByCreatedAtDesc(
                userId,
                contentFingerprint,
                achievementRecordId
        ).ifPresent(duplicate -> {
            job.setFairnessStatus(AbilityAssessmentFairnessStatus.DUPLICATE_REVIEW);
            job.setDuplicateOfJobId(duplicate.getId());
            job.setStatus(AbilityAssessmentJobStatus.REVIEW_REQUIRED);
        });
        return jobRepository.save(job);
    }

    @Transactional
    public void markFairnessStatus(Long jobId, AbilityAssessmentFairnessStatus status) {
        AbilityAssessmentJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null || status == null || job.getFairnessStatus() == AbilityAssessmentFairnessStatus.DUPLICATE_REVIEW) {
            return;
        }
        job.setFairnessStatus(status);
    }

    @Transactional
    public AbilityAssessmentJobEntity markRunning(Long jobId, String modelName) {
        AbilityAssessmentJobEntity job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(AbilityAssessmentJobStatus.RUNNING);
        job.setModelName(modelName);
        job.setAttemptCount(job.getAttemptCount() + 1);
        job.setStartedAt(Instant.now());
        job.setErrorMessage(null);
        return job;
    }

    @Transactional
    public AbilityEvidenceAssessmentEntity saveAssessment(
            Long jobId,
            EvidenceAssessmentMaterial material
    ) {
        AbilityEvidenceAssessmentEntity assessment = assessmentRepository.findByJobId(jobId)
                .orElseGet(AbilityEvidenceAssessmentEntity::new);
        assessment.setJobId(jobId);
        assessment.setNormalizedActivityType(material.normalizedActivityType());
        assessment.setActivityDifficulty(material.activityDifficulty());
        assessment.setActivityDifficultyConfidence(material.activityDifficultyConfidence());
        assessment.setCompletionQuality(material.completionQuality());
        assessment.setCompletionQualityConfidence(material.completionQualityConfidence());
        assessment.setPersonalContribution(material.personalContribution());
        assessment.setPersonalContributionConfidence(material.personalContributionConfidence());
        assessment.setAssessmentConfidence(material.assessmentConfidence());
        assessment.setEvidenceFindingsJson(material.evidenceFindingsJson());
        assessment.setNoveltyFeaturesJson(material.noveltyFeaturesJson());
        assessment.setRiskFlagsJson(material.riskFlagsJson());
        assessment.setJudgeRecommendationJson(material.judgeRecommendationJson());
        assessment.setRawResponseJson(material.rawResponseJson());
        assessment.setEvidenceHash(material.evidenceHash());
        assessment.setPromptVersion(material.promptVersion());
        assessment.setRubricVersion(material.rubricVersion());
        assessment.setModelName(material.modelName());
        assessment = assessmentRepository.save(assessment);

        List<AbilityEvidenceDimensionEntity> oldDimensions =
                dimensionRepository.findByAssessmentIdOrderByRelevanceDesc(assessment.getId());
        dimensionRepository.deleteAllInBatch(oldDimensions);
        for (EvidenceAssessmentDimensionMaterial item : material.dimensions()) {
            AbilityEvidenceDimensionEntity dimension = new AbilityEvidenceDimensionEntity();
            dimension.setAssessmentId(assessment.getId());
            dimension.setDimensionName(item.dimensionName());
            dimension.setNormalizedDimension(item.normalizedDimension());
            dimension.setRelevance(item.relevance());
            dimension.setRelevanceConfidence(item.relevanceConfidence());
            dimension.setClaimedOutcome(item.claimedOutcome());
            dimension.setEvidenceRefsJson(item.evidenceRefsJson());
            dimensionRepository.save(dimension);
        }

        AbilityAssessmentJobEntity job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(AbilityAssessmentJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setModelName(material.modelName());
        jobRepository.save(job);
        return assessment;
    }

    @Transactional
    public void markFailed(Long jobId, Throwable error) {
        AbilityAssessmentJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(AbilityAssessmentJobStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(compact(error == null ? "Unknown error" : error.getMessage(), 1200));
    }

    @Transactional(readOnly = true)
    public AbilityAssessmentJobEntity getJob(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow();
    }

    private String compact(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public record EvidenceAssessmentMaterial(
            String normalizedActivityType,
            java.math.BigDecimal activityDifficulty,
            java.math.BigDecimal activityDifficultyConfidence,
            java.math.BigDecimal completionQuality,
            java.math.BigDecimal completionQualityConfidence,
            java.math.BigDecimal personalContribution,
            java.math.BigDecimal personalContributionConfidence,
            java.math.BigDecimal assessmentConfidence,
            String evidenceFindingsJson,
            String noveltyFeaturesJson,
            String riskFlagsJson,
            String judgeRecommendationJson,
            String rawResponseJson,
            String evidenceHash,
            String promptVersion,
            String rubricVersion,
            String modelName,
            List<EvidenceAssessmentDimensionMaterial> dimensions
    ) {
    }

    public record EvidenceAssessmentDimensionMaterial(
            String dimensionName,
            String normalizedDimension,
            java.math.BigDecimal relevance,
            java.math.BigDecimal relevanceConfidence,
            String claimedOutcome,
            String evidenceRefsJson
    ) {
    }
}
