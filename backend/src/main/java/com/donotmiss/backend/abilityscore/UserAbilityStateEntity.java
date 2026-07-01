package com.donotmiss.backend.abilityscore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "user_ability_states",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ability_state_user_dimension",
                columnNames = {"user_id", "normalized_dimension"}
        ),
        indexes = {
                @Index(name = "idx_ability_state_user_score", columnList = "user_id,ability_score"),
                @Index(name = "idx_ability_state_user_updated", columnList = "user_id,updated_at")
        }
)
public class UserAbilityStateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "dimension_name", nullable = false, length = 100)
    private String dimensionName;

    @Column(name = "normalized_dimension", nullable = false, length = 100)
    private String normalizedDimension;

    @Column(name = "experience_score", nullable = false, precision = 14, scale = 4)
    private BigDecimal experienceScore = BigDecimal.ZERO;

    @Column(name = "ability_score", nullable = false, precision = 7, scale = 4)
    private BigDecimal abilityScore = BigDecimal.ZERO;

    @Column(name = "ability_uncertainty", nullable = false, precision = 5, scale = 4)
    private BigDecimal abilityUncertainty = BigDecimal.ONE;

    @Column(name = "rank_name", nullable = false, length = 48)
    private String rankName = "UNRATED";

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
        if (experienceScore == null) {
            experienceScore = BigDecimal.ZERO;
        }
        if (abilityScore == null) {
            abilityScore = BigDecimal.ZERO;
        }
        if (abilityUncertainty == null) {
            abilityUncertainty = BigDecimal.ONE;
        }
        if (rankName == null || rankName.isBlank()) {
            rankName = "UNRATED";
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

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public BigDecimal getExperienceScore() {
        return experienceScore;
    }

    public void setExperienceScore(BigDecimal experienceScore) {
        this.experienceScore = experienceScore;
    }

    public BigDecimal getAbilityScore() {
        return abilityScore;
    }

    public void setAbilityScore(BigDecimal abilityScore) {
        this.abilityScore = abilityScore;
    }

    public BigDecimal getAbilityUncertainty() {
        return abilityUncertainty;
    }

    public void setAbilityUncertainty(BigDecimal abilityUncertainty) {
        this.abilityUncertainty = abilityUncertainty;
    }

    public String getRankName() {
        return rankName;
    }

    public void setRankName(String rankName) {
        this.rankName = rankName;
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
