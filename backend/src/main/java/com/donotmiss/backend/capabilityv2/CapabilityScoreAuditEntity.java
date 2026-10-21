package com.donotmiss.backend.capabilityv2;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "capability_v2_score_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_capability_v2_score_evidence_rule",
                columnNames = {"evidence_link_id", "scoring_rule_version"}
        ),
        indexes = {
                @Index(name = "idx_capability_v2_score_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_capability_v2_score_bucket_created", columnList = "bucket_id,created_at"),
                @Index(name = "idx_capability_v2_score_user_bucket", columnList = "user_id,bucket_id,created_at")
        }
)
public class CapabilityScoreAuditEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;
    @Column(name = "bucket_id", nullable = false)
    private Long bucketId;
    @Column(name = "user_state_id")
    private Long userStateId;
    @Column(name = "evidence_link_id", nullable = false)
    private Long evidenceLinkId;
    @Column(name = "legacy_score_result_id")
    private Long legacyScoreResultId;
    @Column(name = "scoring_rule_version", nullable = false, length = 80)
    private String scoringRuleVersion;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "old_experience_score", precision = 14, scale = 4)
    private BigDecimal oldExperienceScore;
    @Column(name = "experience_gain", precision = 10, scale = 4)
    private BigDecimal experienceGain;
    @Column(name = "new_experience_score", precision = 14, scale = 4)
    private BigDecimal newExperienceScore;
    @Column(name = "old_ability_score", precision = 7, scale = 4)
    private BigDecimal oldAbilityScore;
    @Column(name = "new_ability_score", precision = 7, scale = 4)
    private BigDecimal newAbilityScore;
    @Column(name = "old_ability_uncertainty", precision = 5, scale = 4)
    private BigDecimal oldAbilityUncertainty;
    @Column(name = "new_ability_uncertainty", precision = 5, scale = 4)
    private BigDecimal newAbilityUncertainty;
    @Column(name = "factor_snapshot_json", nullable = false, columnDefinition = "json")
    private String factorSnapshotJson = "{}";
    @Column(name = "agent_run_id")
    private Long agentRunId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (factorSnapshotJson == null || factorSnapshotJson.isBlank()) factorSnapshotJson = "{}";
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getBucketId() { return bucketId; }
    public void setBucketId(Long bucketId) { this.bucketId = bucketId; }
    public Long getUserStateId() { return userStateId; }
    public void setUserStateId(Long userStateId) { this.userStateId = userStateId; }
    public Long getEvidenceLinkId() { return evidenceLinkId; }
    public void setEvidenceLinkId(Long evidenceLinkId) { this.evidenceLinkId = evidenceLinkId; }
    public Long getLegacyScoreResultId() { return legacyScoreResultId; }
    public void setLegacyScoreResultId(Long legacyScoreResultId) { this.legacyScoreResultId = legacyScoreResultId; }
    public String getScoringRuleVersion() { return scoringRuleVersion; }
    public void setScoringRuleVersion(String scoringRuleVersion) { this.scoringRuleVersion = scoringRuleVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getOldExperienceScore() { return oldExperienceScore; }
    public void setOldExperienceScore(BigDecimal oldExperienceScore) { this.oldExperienceScore = oldExperienceScore; }
    public BigDecimal getExperienceGain() { return experienceGain; }
    public void setExperienceGain(BigDecimal experienceGain) { this.experienceGain = experienceGain; }
    public BigDecimal getNewExperienceScore() { return newExperienceScore; }
    public void setNewExperienceScore(BigDecimal newExperienceScore) { this.newExperienceScore = newExperienceScore; }
    public BigDecimal getOldAbilityScore() { return oldAbilityScore; }
    public void setOldAbilityScore(BigDecimal oldAbilityScore) { this.oldAbilityScore = oldAbilityScore; }
    public BigDecimal getNewAbilityScore() { return newAbilityScore; }
    public void setNewAbilityScore(BigDecimal newAbilityScore) { this.newAbilityScore = newAbilityScore; }
    public BigDecimal getOldAbilityUncertainty() { return oldAbilityUncertainty; }
    public void setOldAbilityUncertainty(BigDecimal oldAbilityUncertainty) { this.oldAbilityUncertainty = oldAbilityUncertainty; }
    public BigDecimal getNewAbilityUncertainty() { return newAbilityUncertainty; }
    public void setNewAbilityUncertainty(BigDecimal newAbilityUncertainty) { this.newAbilityUncertainty = newAbilityUncertainty; }
    public String getFactorSnapshotJson() { return factorSnapshotJson; }
    public void setFactorSnapshotJson(String factorSnapshotJson) { this.factorSnapshotJson = factorSnapshotJson; }
    public Long getAgentRunId() { return agentRunId; }
    public void setAgentRunId(Long agentRunId) { this.agentRunId = agentRunId; }
    public Instant getCreatedAt() { return createdAt; }
}
