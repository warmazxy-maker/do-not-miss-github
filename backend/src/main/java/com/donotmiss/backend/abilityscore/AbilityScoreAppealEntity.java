package com.donotmiss.backend.abilityscore;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "ability_score_appeals",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ability_appeal_request", columnNames = "request_id"),
                @UniqueConstraint(
                        name = "uk_ability_appeal_replacement",
                        columnNames = {
                                "score_result_id",
                                "replacement_assessment_id",
                                "normalized_dimension",
                                "request_type"
                        }
                )
        },
        indexes = {
                @Index(name = "idx_ability_appeal_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_ability_appeal_status_created", columnList = "status,created_at")
        }
)
public class AbilityScoreAppealEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 32)
    private AbilityScoreAppealType requestType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AbilityScoreAppealStatus status = AbilityScoreAppealStatus.PENDING;

    @Column(name = "score_result_id", nullable = false)
    private Long scoreResultId;

    @Column(name = "replacement_assessment_id")
    private Long replacementAssessmentId;

    @Column(name = "normalized_dimension", nullable = false, length = 100)
    private String normalizedDimension;

    @Column(nullable = false, length = 1200)
    private String reason;

    @Column(name = "evidence_note", length = 2000)
    private String evidenceNote;

    @Column(length = 2000)
    private String resolution;

    @Column(name = "reviewer_id", length = 80)
    private String reviewerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (status == null) {
            status = AbilityScoreAppealStatus.PENDING;
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

    public AbilityScoreAppealType getRequestType() {
        return requestType;
    }

    public void setRequestType(AbilityScoreAppealType requestType) {
        this.requestType = requestType;
    }

    public AbilityScoreAppealStatus getStatus() {
        return status;
    }

    public void setStatus(AbilityScoreAppealStatus status) {
        this.status = status;
    }

    public Long getScoreResultId() {
        return scoreResultId;
    }

    public void setScoreResultId(Long scoreResultId) {
        this.scoreResultId = scoreResultId;
    }

    public Long getReplacementAssessmentId() {
        return replacementAssessmentId;
    }

    public void setReplacementAssessmentId(Long replacementAssessmentId) {
        this.replacementAssessmentId = replacementAssessmentId;
    }

    public String getNormalizedDimension() {
        return normalizedDimension;
    }

    public void setNormalizedDimension(String normalizedDimension) {
        this.normalizedDimension = normalizedDimension;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getEvidenceNote() {
        return evidenceNote;
    }

    public void setEvidenceNote(String evidenceNote) {
        this.evidenceNote = evidenceNote;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
