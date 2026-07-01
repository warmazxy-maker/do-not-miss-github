package com.donotmiss.backend.abilityscore;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "ability_score_results",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ability_score_request_dimension",
                        columnNames = {"request_id", "normalized_dimension"}
                ),
                @UniqueConstraint(
                        name = "uk_ability_score_assessment_rule",
                        columnNames = {"assessment_id", "normalized_dimension", "scoring_rule_version"}
                )
        },
        indexes = {
                @Index(name = "idx_ability_score_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_ability_score_state_created", columnList = "ability_state_id,created_at"),
                @Index(name = "idx_ability_score_status_created", columnList = "status,created_at")
        }
)
public class AbilityScoreResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "achievement_record_id", nullable = false)
    private Long achievementRecordId;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "ability_state_id", nullable = false)
    private Long abilityStateId;

    @Column(name = "dimension_name", nullable = false, length = 100)
    private String dimensionName;

    @Column(name = "normalized_dimension", nullable = false, length = 100)
    private String normalizedDimension;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AbilityScoreResultStatus status = AbilityScoreResultStatus.PROVISIONAL;

    @Column(name = "old_experience_score", nullable = false, precision = 14, scale = 4)
    private BigDecimal oldExperienceScore;

    @Column(name = "provisional_experience_gain", nullable = false, precision = 10, scale = 4)
    private BigDecimal provisionalExperienceGain;

    @Column(name = "verified_experience_gain", nullable = false, precision = 10, scale = 4)
    private BigDecimal verifiedExperienceGain;

    @Column(name = "new_experience_score", nullable = false, precision = 14, scale = 4)
    private BigDecimal newExperienceScore;

    @Column(name = "old_ability_score", nullable = false, precision = 7, scale = 4)
    private BigDecimal oldAbilityScore;

    @Column(name = "new_ability_score", nullable = false, precision = 7, scale = 4)
    private BigDecimal newAbilityScore;

    @Column(name = "old_ability_uncertainty", nullable = false, precision = 5, scale = 4)
    private BigDecimal oldAbilityUncertainty;

    @Column(name = "new_ability_uncertainty", nullable = false, precision = 5, scale = 4)
    private BigDecimal newAbilityUncertainty;

    @Column(name = "profile_confidence_snapshot", nullable = false, precision = 5, scale = 4)
    private BigDecimal profileConfidenceSnapshot;

    @Column(name = "growth_value", nullable = false, precision = 8, scale = 6)
    private BigDecimal growthValue;

    @Column(name = "verification_strength", nullable = false, precision = 8, scale = 6)
    private BigDecimal verificationStrength;

    @Column(name = "profile_confidence_multiplier", nullable = false, precision = 6, scale = 4)
    private BigDecimal profileConfidenceMultiplier;

    @Column(name = "difficulty_match_multiplier", nullable = false, precision = 6, scale = 4)
    private BigDecimal difficultyMatchMultiplier;

    @Column(name = "factor_snapshot_json", nullable = false, columnDefinition = "json")
    private String factorSnapshotJson = "{}";

    @Column(name = "judge_flags_json", nullable = false, columnDefinition = "json")
    private String judgeFlagsJson = "[]";

    @Column(name = "scoring_rule_version", nullable = false, length = 80)
    private String scoringRuleVersion;

    @Column(name = "history_snapshot_version", nullable = false, length = 80)
    private String historySnapshotVersion;

    @Column(name = "supersedes_result_id")
    private Long supersedesResultId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (status == null) {
            status = AbilityScoreResultStatus.PROVISIONAL;
        }
        if (factorSnapshotJson == null) {
            factorSnapshotJson = "{}";
        }
        if (judgeFlagsJson == null) {
            judgeFlagsJson = "[]";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getAchievementRecordId() {
        return achievementRecordId;
    }

    public void setAchievementRecordId(Long achievementRecordId) {
        this.achievementRecordId = achievementRecordId;
    }

    public Long getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(Long assessmentId) {
        this.assessmentId = assessmentId;
    }

    public Long getAbilityStateId() {
        return abilityStateId;
    }

    public void setAbilityStateId(Long abilityStateId) {
        this.abilityStateId = abilityStateId;
    }

    public String getDimensionName() {
        return dimensionName;
    }

    public void setDimensionName(String dimensionName) {
        this.dimensionName = dimensionName;
    }

    public String getNormalizedDimension() {
        return normalizedDimension;
    }

    public void setNormalizedDimension(String normalizedDimension) {
        this.normalizedDimension = normalizedDimension;
    }

    public AbilityScoreResultStatus getStatus() {
        return status;
    }

    public void setStatus(AbilityScoreResultStatus status) {
        this.status = status;
    }

    public BigDecimal getOldExperienceScore() {
        return oldExperienceScore;
    }

    public void setOldExperienceScore(BigDecimal oldExperienceScore) {
        this.oldExperienceScore = oldExperienceScore;
    }

    public BigDecimal getProvisionalExperienceGain() {
        return provisionalExperienceGain;
    }

    public void setProvisionalExperienceGain(BigDecimal provisionalExperienceGain) {
        this.provisionalExperienceGain = provisionalExperienceGain;
    }

    public BigDecimal getVerifiedExperienceGain() {
        return verifiedExperienceGain;
    }

    public void setVerifiedExperienceGain(BigDecimal verifiedExperienceGain) {
        this.verifiedExperienceGain = verifiedExperienceGain;
    }

    public BigDecimal getNewExperienceScore() {
        return newExperienceScore;
    }

    public void setNewExperienceScore(BigDecimal newExperienceScore) {
        this.newExperienceScore = newExperienceScore;
    }

    public BigDecimal getOldAbilityScore() {
        return oldAbilityScore;
    }

    public void setOldAbilityScore(BigDecimal oldAbilityScore) {
        this.oldAbilityScore = oldAbilityScore;
    }

    public BigDecimal getNewAbilityScore() {
        return newAbilityScore;
    }

    public void setNewAbilityScore(BigDecimal newAbilityScore) {
        this.newAbilityScore = newAbilityScore;
    }

    public BigDecimal getOldAbilityUncertainty() {
        return oldAbilityUncertainty;
    }

    public void setOldAbilityUncertainty(BigDecimal oldAbilityUncertainty) {
        this.oldAbilityUncertainty = oldAbilityUncertainty;
    }

    public BigDecimal getNewAbilityUncertainty() {
        return newAbilityUncertainty;
    }

    public void setNewAbilityUncertainty(BigDecimal newAbilityUncertainty) {
        this.newAbilityUncertainty = newAbilityUncertainty;
    }

    public BigDecimal getProfileConfidenceSnapshot() {
        return profileConfidenceSnapshot;
    }

    public void setProfileConfidenceSnapshot(BigDecimal profileConfidenceSnapshot) {
        this.profileConfidenceSnapshot = profileConfidenceSnapshot;
    }

    public BigDecimal getGrowthValue() {
        return growthValue;
    }

    public void setGrowthValue(BigDecimal growthValue) {
        this.growthValue = growthValue;
    }

    public BigDecimal getVerificationStrength() {
        return verificationStrength;
    }

    public void setVerificationStrength(BigDecimal verificationStrength) {
        this.verificationStrength = verificationStrength;
    }

    public BigDecimal getProfileConfidenceMultiplier() {
        return profileConfidenceMultiplier;
    }

    public void setProfileConfidenceMultiplier(BigDecimal profileConfidenceMultiplier) {
        this.profileConfidenceMultiplier = profileConfidenceMultiplier;
    }

    public BigDecimal getDifficultyMatchMultiplier() {
        return difficultyMatchMultiplier;
    }

    public void setDifficultyMatchMultiplier(BigDecimal difficultyMatchMultiplier) {
        this.difficultyMatchMultiplier = difficultyMatchMultiplier;
    }

    public String getFactorSnapshotJson() {
        return factorSnapshotJson;
    }

    public void setFactorSnapshotJson(String factorSnapshotJson) {
        this.factorSnapshotJson = factorSnapshotJson;
    }

    public String getJudgeFlagsJson() {
        return judgeFlagsJson;
    }

    public void setJudgeFlagsJson(String judgeFlagsJson) {
        this.judgeFlagsJson = judgeFlagsJson;
    }

    public String getScoringRuleVersion() {
        return scoringRuleVersion;
    }

    public void setScoringRuleVersion(String scoringRuleVersion) {
        this.scoringRuleVersion = scoringRuleVersion;
    }

    public String getHistorySnapshotVersion() {
        return historySnapshotVersion;
    }

    public void setHistorySnapshotVersion(String historySnapshotVersion) {
        this.historySnapshotVersion = historySnapshotVersion;
    }

    public Long getSupersedesResultId() {
        return supersedesResultId;
    }

    public void setSupersedesResultId(Long supersedesResultId) {
        this.supersedesResultId = supersedesResultId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
