package com.donotmiss.backend.abilityscore;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "ability_evidence_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uk_ability_evidence_job", columnNames = "job_id"),
        indexes = @Index(name = "idx_ability_evidence_hash", columnList = "evidence_hash")
)
public class AbilityEvidenceAssessmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "normalized_activity_type", nullable = false, length = 48)
    private String normalizedActivityType;

    @Column(name = "activity_difficulty", nullable = false, precision = 7, scale = 4)
    private BigDecimal activityDifficulty;

    @Column(name = "activity_difficulty_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal activityDifficultyConfidence;

    @Column(name = "completion_quality", nullable = false, precision = 5, scale = 4)
    private BigDecimal completionQuality;

    @Column(name = "completion_quality_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal completionQualityConfidence;

    @Column(name = "personal_contribution", nullable = false, precision = 5, scale = 4)
    private BigDecimal personalContribution;

    @Column(name = "personal_contribution_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal personalContributionConfidence;

    @Column(name = "assessment_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal assessmentConfidence;

    @Column(name = "evidence_findings_json", nullable = false, columnDefinition = "json")
    private String evidenceFindingsJson = "[]";

    @Column(name = "novelty_features_json", nullable = false, columnDefinition = "json")
    private String noveltyFeaturesJson = "{}";

    @Column(name = "risk_flags_json", nullable = false, columnDefinition = "json")
    private String riskFlagsJson = "[]";

    @Column(name = "judge_recommendation_json", nullable = false, columnDefinition = "json")
    private String judgeRecommendationJson = "{}";

    @Column(name = "raw_response_json", nullable = false, columnDefinition = "json")
    private String rawResponseJson = "{}";

    @Column(name = "evidence_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String evidenceHash;

    @Column(name = "prompt_version", nullable = false, length = 80)
    private String promptVersion;

    @Column(name = "rubric_version", nullable = false, length = 80)
    private String rubricVersion;

    @Column(name = "model_name", nullable = false, length = 120)
    private String modelName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (evidenceFindingsJson == null) {
            evidenceFindingsJson = "[]";
        }
        if (noveltyFeaturesJson == null) {
            noveltyFeaturesJson = "{}";
        }
        if (riskFlagsJson == null) {
            riskFlagsJson = "[]";
        }
        if (judgeRecommendationJson == null) {
            judgeRecommendationJson = "{}";
        }
        if (rawResponseJson == null) {
            rawResponseJson = "{}";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getNormalizedActivityType() {
        return normalizedActivityType;
    }

    public void setNormalizedActivityType(String normalizedActivityType) {
        this.normalizedActivityType = normalizedActivityType;
    }

    public BigDecimal getActivityDifficulty() {
        return activityDifficulty;
    }

    public void setActivityDifficulty(BigDecimal activityDifficulty) {
        this.activityDifficulty = activityDifficulty;
    }

    public BigDecimal getActivityDifficultyConfidence() {
        return activityDifficultyConfidence;
    }

    public void setActivityDifficultyConfidence(BigDecimal activityDifficultyConfidence) {
        this.activityDifficultyConfidence = activityDifficultyConfidence;
    }

    public BigDecimal getCompletionQuality() {
        return completionQuality;
    }

    public void setCompletionQuality(BigDecimal completionQuality) {
        this.completionQuality = completionQuality;
    }

    public BigDecimal getCompletionQualityConfidence() {
        return completionQualityConfidence;
    }

    public void setCompletionQualityConfidence(BigDecimal completionQualityConfidence) {
        this.completionQualityConfidence = completionQualityConfidence;
    }

    public BigDecimal getPersonalContribution() {
        return personalContribution;
    }

    public void setPersonalContribution(BigDecimal personalContribution) {
        this.personalContribution = personalContribution;
    }

    public BigDecimal getPersonalContributionConfidence() {
        return personalContributionConfidence;
    }

    public void setPersonalContributionConfidence(BigDecimal personalContributionConfidence) {
        this.personalContributionConfidence = personalContributionConfidence;
    }

    public BigDecimal getAssessmentConfidence() {
        return assessmentConfidence;
    }

    public void setAssessmentConfidence(BigDecimal assessmentConfidence) {
        this.assessmentConfidence = assessmentConfidence;
    }

    public String getEvidenceFindingsJson() {
        return evidenceFindingsJson;
    }

    public void setEvidenceFindingsJson(String evidenceFindingsJson) {
        this.evidenceFindingsJson = evidenceFindingsJson;
    }

    public String getNoveltyFeaturesJson() {
        return noveltyFeaturesJson;
    }

    public void setNoveltyFeaturesJson(String noveltyFeaturesJson) {
        this.noveltyFeaturesJson = noveltyFeaturesJson;
    }

    public String getRiskFlagsJson() {
        return riskFlagsJson;
    }

    public void setRiskFlagsJson(String riskFlagsJson) {
        this.riskFlagsJson = riskFlagsJson;
    }

    public String getJudgeRecommendationJson() {
        return judgeRecommendationJson;
    }

    public void setJudgeRecommendationJson(String judgeRecommendationJson) {
        this.judgeRecommendationJson = judgeRecommendationJson;
    }

    public String getRawResponseJson() {
        return rawResponseJson;
    }

    public void setRawResponseJson(String rawResponseJson) {
        this.rawResponseJson = rawResponseJson;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public void setEvidenceHash(String evidenceHash) {
        this.evidenceHash = evidenceHash;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getRubricVersion() {
        return rubricVersion;
    }

    public void setRubricVersion(String rubricVersion) {
        this.rubricVersion = rubricVersion;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
