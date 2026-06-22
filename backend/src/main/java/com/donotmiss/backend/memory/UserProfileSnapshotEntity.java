package com.donotmiss.backend.memory;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "user_profile_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_profile_snapshots_user", columnNames = "user_id"),
        indexes = @Index(name = "idx_user_profile_snapshots_dirty", columnList = "dirty,updated_at")
)
public class UserProfileSnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(nullable = false, length = 1200)
    private String summary;

    @Column(name = "strengths_json", nullable = false, columnDefinition = "longtext")
    private String strengthsJson;

    @Column(name = "preferred_categories_json", nullable = false, columnDefinition = "longtext")
    private String preferredCategoriesJson;

    @Column(name = "preferred_locations_json", nullable = false, columnDefinition = "longtext")
    private String preferredLocationsJson;

    @Column(name = "benefit_preferences_json", nullable = false, columnDefinition = "longtext")
    private String benefitPreferencesJson;

    @Column(name = "evidence_keywords_json", nullable = false, columnDefinition = "longtext")
    private String evidenceKeywordsJson;

    @Column(name = "recent_signals_json", nullable = false, columnDefinition = "longtext")
    private String recentSignalsJson;

    @Column(name = "completed_count", nullable = false)
    private long completedCount;

    @Column(name = "active_challenge_count", nullable = false)
    private long activeChallengeCount;

    @Column(name = "coach_log_count", nullable = false)
    private long coachLogCount;

    @Column(nullable = false)
    private boolean dirty = true;

    @Column(name = "generated_by", nullable = false, length = 80)
    private String generatedBy = "pending";

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (summary == null || summary.isBlank()) {
            summary = "画像待生成";
        }
        if (strengthsJson == null) {
            strengthsJson = "[]";
        }
        if (preferredCategoriesJson == null) {
            preferredCategoriesJson = "[]";
        }
        if (preferredLocationsJson == null) {
            preferredLocationsJson = "[]";
        }
        if (benefitPreferencesJson == null) {
            benefitPreferencesJson = "[]";
        }
        if (evidenceKeywordsJson == null) {
            evidenceKeywordsJson = "[]";
        }
        if (recentSignalsJson == null) {
            recentSignalsJson = "[]";
        }
        if (generatedBy == null || generatedBy.isBlank()) {
            generatedBy = "pending";
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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStrengthsJson() {
        return strengthsJson;
    }

    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }

    public String getPreferredCategoriesJson() {
        return preferredCategoriesJson;
    }

    public void setPreferredCategoriesJson(String preferredCategoriesJson) {
        this.preferredCategoriesJson = preferredCategoriesJson;
    }

    public String getPreferredLocationsJson() {
        return preferredLocationsJson;
    }

    public void setPreferredLocationsJson(String preferredLocationsJson) {
        this.preferredLocationsJson = preferredLocationsJson;
    }

    public String getBenefitPreferencesJson() {
        return benefitPreferencesJson;
    }

    public void setBenefitPreferencesJson(String benefitPreferencesJson) {
        this.benefitPreferencesJson = benefitPreferencesJson;
    }

    public String getEvidenceKeywordsJson() {
        return evidenceKeywordsJson;
    }

    public void setEvidenceKeywordsJson(String evidenceKeywordsJson) {
        this.evidenceKeywordsJson = evidenceKeywordsJson;
    }

    public String getRecentSignalsJson() {
        return recentSignalsJson;
    }

    public void setRecentSignalsJson(String recentSignalsJson) {
        this.recentSignalsJson = recentSignalsJson;
    }

    public long getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(long completedCount) {
        this.completedCount = completedCount;
    }

    public long getActiveChallengeCount() {
        return activeChallengeCount;
    }

    public void setActiveChallengeCount(long activeChallengeCount) {
        this.activeChallengeCount = activeChallengeCount;
    }

    public long getCoachLogCount() {
        return coachLogCount;
    }

    public void setCoachLogCount(long coachLogCount) {
        this.coachLogCount = coachLogCount;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }

    public void setGeneratedBy(String generatedBy) {
        this.generatedBy = generatedBy;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
