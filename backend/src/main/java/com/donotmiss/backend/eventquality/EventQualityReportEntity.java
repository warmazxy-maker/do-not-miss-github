package com.donotmiss.backend.eventquality;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "event_quality_reports",
        indexes = {
                @Index(name = "idx_event_quality_score", columnList = "quality_score"),
                @Index(name = "idx_event_quality_suggestion", columnList = "review_suggestion")
        }
)
public class EventQualityReportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private Long eventId;

    @Column(name = "quality_score", nullable = false)
    private int qualityScore;

    @Column(name = "quality_level", nullable = false, length = 32)
    private String qualityLevel;

    @Column(name = "review_suggestion", nullable = false, length = 32)
    private String reviewSuggestion;

    @Column(nullable = false, length = 32)
    private String difficulty;

    @Column(length = 800)
    private String summary;

    @Column(name = "target_students_json", nullable = false, columnDefinition = "json")
    private String targetStudentsJson = "[]";

    @Column(name = "prerequisites_json", nullable = false, columnDefinition = "json")
    private String prerequisitesJson = "[]";

    @Column(name = "learning_outcomes_json", nullable = false, columnDefinition = "json")
    private String learningOutcomesJson = "[]";

    @Column(name = "extracted_tags_json", nullable = false, columnDefinition = "json")
    private String extractedTagsJson = "[]";

    @Column(name = "ability_impacts_json", nullable = false, columnDefinition = "json")
    private String abilityImpactsJson = "[]";

    @Column(name = "risk_flags_json", nullable = false, columnDefinition = "json")
    private String riskFlagsJson = "[]";

    @Column(name = "missing_fields_json", nullable = false, columnDefinition = "json")
    private String missingFieldsJson = "[]";

    @Column(name = "duplicate_event_ids_json", nullable = false, columnDefinition = "json")
    private String duplicateEventIdsJson = "[]";

    @Column(name = "model_name", nullable = false, length = 120)
    private String modelName;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
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

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public int getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(int qualityScore) {
        this.qualityScore = qualityScore;
    }

    public String getQualityLevel() {
        return qualityLevel;
    }

    public void setQualityLevel(String qualityLevel) {
        this.qualityLevel = qualityLevel;
    }

    public String getReviewSuggestion() {
        return reviewSuggestion;
    }

    public void setReviewSuggestion(String reviewSuggestion) {
        this.reviewSuggestion = reviewSuggestion;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTargetStudentsJson() {
        return targetStudentsJson;
    }

    public void setTargetStudentsJson(String targetStudentsJson) {
        this.targetStudentsJson = targetStudentsJson;
    }

    public String getPrerequisitesJson() {
        return prerequisitesJson;
    }

    public void setPrerequisitesJson(String prerequisitesJson) {
        this.prerequisitesJson = prerequisitesJson;
    }

    public String getLearningOutcomesJson() {
        return learningOutcomesJson;
    }

    public void setLearningOutcomesJson(String learningOutcomesJson) {
        this.learningOutcomesJson = learningOutcomesJson;
    }

    public String getExtractedTagsJson() {
        return extractedTagsJson;
    }

    public void setExtractedTagsJson(String extractedTagsJson) {
        this.extractedTagsJson = extractedTagsJson;
    }

    public String getAbilityImpactsJson() {
        return abilityImpactsJson;
    }

    public void setAbilityImpactsJson(String abilityImpactsJson) {
        this.abilityImpactsJson = abilityImpactsJson;
    }

    public String getRiskFlagsJson() {
        return riskFlagsJson;
    }

    public void setRiskFlagsJson(String riskFlagsJson) {
        this.riskFlagsJson = riskFlagsJson;
    }

    public String getMissingFieldsJson() {
        return missingFieldsJson;
    }

    public void setMissingFieldsJson(String missingFieldsJson) {
        this.missingFieldsJson = missingFieldsJson;
    }

    public String getDuplicateEventIdsJson() {
        return duplicateEventIdsJson;
    }

    public void setDuplicateEventIdsJson(String duplicateEventIdsJson) {
        this.duplicateEventIdsJson = duplicateEventIdsJson;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
