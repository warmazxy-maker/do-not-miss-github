package com.donotmiss.backend.capabilityv2;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "user_capability_v2_states",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_capability_v2_state",
                columnNames = {"user_id", "bucket_id"}
        ),
        indexes = {
                @Index(name = "idx_user_capability_v2_state_user_score", columnList = "user_id,ability_score"),
                @Index(name = "idx_user_capability_v2_state_bucket", columnList = "bucket_id,updated_at")
        }
)
public class UserCapabilityStateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "bucket_id", nullable = false)
    private Long bucketId;

    @Column(name = "experience_score", nullable = false, precision = 14, scale = 4)
    private BigDecimal experienceScore = BigDecimal.ZERO;

    @Column(name = "ability_score", nullable = false, precision = 7, scale = 4)
    private BigDecimal abilityScore = BigDecimal.ZERO;

    @Column(name = "ability_uncertainty", nullable = false, precision = 5, scale = 4)
    private BigDecimal abilityUncertainty = BigDecimal.ONE;

    @Column(name = "rank_name", nullable = false, length = 48)
    private String rankName = "UNRATED";

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Version
    @Column(name = "state_version", nullable = false)
    private long stateVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (experienceScore == null) experienceScore = BigDecimal.ZERO;
        if (abilityScore == null) abilityScore = BigDecimal.ZERO;
        if (abilityUncertainty == null) abilityUncertainty = BigDecimal.ONE;
        if (rankName == null || rankName.isBlank()) rankName = "UNRATED";
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getBucketId() { return bucketId; }
    public void setBucketId(Long bucketId) { this.bucketId = bucketId; }
    public BigDecimal getExperienceScore() { return experienceScore; }
    public void setExperienceScore(BigDecimal experienceScore) { this.experienceScore = experienceScore; }
    public BigDecimal getAbilityScore() { return abilityScore; }
    public void setAbilityScore(BigDecimal abilityScore) { this.abilityScore = abilityScore; }
    public BigDecimal getAbilityUncertainty() { return abilityUncertainty; }
    public void setAbilityUncertainty(BigDecimal abilityUncertainty) { this.abilityUncertainty = abilityUncertainty; }
    public String getRankName() { return rankName; }
    public void setRankName(String rankName) { this.rankName = rankName; }
    public int getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(int evidenceCount) { this.evidenceCount = evidenceCount; }
    public long getStateVersion() { return stateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
