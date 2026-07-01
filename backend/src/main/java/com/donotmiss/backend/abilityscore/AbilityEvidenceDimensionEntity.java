package com.donotmiss.backend.abilityscore;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "ability_evidence_dimensions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ability_evidence_dimension",
                columnNames = {"assessment_id", "normalized_dimension"}
        ),
        indexes = @Index(name = "idx_ability_dimension_normalized", columnList = "normalized_dimension")
)
public class AbilityEvidenceDimensionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "dimension_name", nullable = false, length = 100)
    private String dimensionName;

    @Column(name = "normalized_dimension", nullable = false, length = 100)
    private String normalizedDimension;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal relevance;

    @Column(name = "relevance_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal relevanceConfidence;

    @Column(name = "claimed_outcome", length = 600)
    private String claimedOutcome;

    @Column(name = "evidence_refs_json", nullable = false, columnDefinition = "json")
    private String evidenceRefsJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (evidenceRefsJson == null) {
            evidenceRefsJson = "[]";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(Long assessmentId) {
        this.assessmentId = assessmentId;
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

    public BigDecimal getRelevance() {
        return relevance;
    }

    public void setRelevance(BigDecimal relevance) {
        this.relevance = relevance;
    }

    public BigDecimal getRelevanceConfidence() {
        return relevanceConfidence;
    }

    public void setRelevanceConfidence(BigDecimal relevanceConfidence) {
        this.relevanceConfidence = relevanceConfidence;
    }

    public String getClaimedOutcome() {
        return claimedOutcome;
    }

    public void setClaimedOutcome(String claimedOutcome) {
        this.claimedOutcome = claimedOutcome;
    }

    public String getEvidenceRefsJson() {
        return evidenceRefsJson;
    }

    public void setEvidenceRefsJson(String evidenceRefsJson) {
        this.evidenceRefsJson = evidenceRefsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
