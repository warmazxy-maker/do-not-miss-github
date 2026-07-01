package com.donotmiss.backend.abilityscore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ability_scoring_profiles")
public class AbilityScoringProfileEntity {
    @Id
    @Column(name = "user_id", length = 80)
    private String userId;

    @Column(name = "profile_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal profileConfidence = new BigDecimal("0.5000");

    @Version
    @Column(name = "confidence_version", nullable = false)
    private long confidenceVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (profileConfidence == null) {
            profileConfidence = new BigDecimal("0.5000");
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public BigDecimal getProfileConfidence() {
        return profileConfidence;
    }

    public void setProfileConfidence(BigDecimal profileConfidence) {
        this.profileConfidence = profileConfidence;
    }

    public long getConfidenceVersion() {
        return confidenceVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
