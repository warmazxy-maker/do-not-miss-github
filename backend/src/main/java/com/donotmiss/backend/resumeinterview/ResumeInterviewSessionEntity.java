package com.donotmiss.backend.resumeinterview;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "resume_interview_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_resume_interview_request", columnNames = "request_id"),
        indexes = {
                @Index(name = "idx_resume_interview_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_resume_interview_status_updated", columnList = "status,updated_at")
        }
)
public class ResumeInterviewSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "target_role", nullable = false, length = 160)
    private String targetRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResumeInterviewSessionStatus status = ResumeInterviewSessionStatus.CREATED;

    @Column(name = "active_resume_snapshot_id")
    private Long activeResumeSnapshotId;

    @Column(name = "final_score_summary_id")
    private Long finalScoreSummaryId;

    @Column(name = "error_message", length = 1200)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (status == null) {
            status = ResumeInterviewSessionStatus.CREATED;
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

    public String getTargetRole() {
        return targetRole;
    }

    public void setTargetRole(String targetRole) {
        this.targetRole = targetRole;
    }

    public ResumeInterviewSessionStatus getStatus() {
        return status;
    }

    public void setStatus(ResumeInterviewSessionStatus status) {
        this.status = status;
    }

    public Long getActiveResumeSnapshotId() {
        return activeResumeSnapshotId;
    }

    public void setActiveResumeSnapshotId(Long activeResumeSnapshotId) {
        this.activeResumeSnapshotId = activeResumeSnapshotId;
    }

    public Long getFinalScoreSummaryId() {
        return finalScoreSummaryId;
    }

    public void setFinalScoreSummaryId(Long finalScoreSummaryId) {
        this.finalScoreSummaryId = finalScoreSummaryId;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
