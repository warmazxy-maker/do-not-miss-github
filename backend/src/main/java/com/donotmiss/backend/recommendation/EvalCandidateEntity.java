package com.donotmiss.backend.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A "not relevant" click proposed as an evaluation case: for this need, this event should not be recommended. */
@Entity
@Table(
        name = "recommendation_eval_candidates",
        uniqueConstraints = @UniqueConstraint(name = "uk_rec_eval_candidate_impression", columnNames = "impression_id"),
        indexes = @Index(name = "idx_rec_eval_candidate_status", columnList = "status,created_at")
)
public class EvalCandidateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "impression_id", nullable = false)
    private Long impressionId;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "need_text", length = 500)
    private String needText;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvalCandidateStatus status = EvalCandidateStatus.PENDING;

    @Column(name = "reviewer_note", length = 500)
    private String reviewerNote;

    @Column(name = "reviewed_by", length = 80)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

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

    public static EvalCandidateEntity from(RecommendationImpressionEntity impression, String reason) {
        EvalCandidateEntity candidate = new EvalCandidateEntity();
        candidate.impressionId = impression.getId();
        candidate.runId = impression.getRunId();
        candidate.userId = impression.getUserId();
        candidate.eventId = impression.getEventId();
        candidate.needText = impression.getNeedText();
        candidate.reason = reason;
        return candidate;
    }

    public void review(EvalCandidateStatus decision, String needText, String note, String reviewer) {
        this.status = decision;
        if (needText != null && !needText.isBlank()) {
            this.needText = needText.trim();
        }
        this.reviewerNote = note;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }

    public void updateReason(String reason) {
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getImpressionId() {
        return impressionId;
    }

    public Long getRunId() {
        return runId;
    }

    public String getUserId() {
        return userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getNeedText() {
        return needText;
    }

    public String getReason() {
        return reason;
    }

    public EvalCandidateStatus getStatus() {
        return status;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
