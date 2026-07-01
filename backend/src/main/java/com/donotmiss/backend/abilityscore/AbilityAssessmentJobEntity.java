package com.donotmiss.backend.abilityscore;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "ability_assessment_jobs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ability_job_request", columnNames = "request_id"),
                @UniqueConstraint(
                        name = "uk_ability_job_evidence_version",
                        columnNames = {"achievement_record_id", "evidence_hash", "prompt_version", "rubric_version"}
                )
        },
        indexes = {
                @Index(name = "idx_ability_job_status_created", columnList = "status,created_at"),
                @Index(name = "idx_ability_job_user_created", columnList = "user_id,created_at")
        }
)
public class AbilityAssessmentJobEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "achievement_record_id", nullable = false)
    private Long achievementRecordId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AbilityAssessmentJobStatus status = AbilityAssessmentJobStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "fairness_status", nullable = false, length = 32)
    private AbilityAssessmentFairnessStatus fairnessStatus = AbilityAssessmentFairnessStatus.CLEAR;

    @Column(name = "evidence_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String evidenceHash;

    @Column(name = "content_fingerprint", length = 64, columnDefinition = "char(64)")
    private String contentFingerprint;

    @Column(name = "duplicate_of_job_id")
    private Long duplicateOfJobId;

    @Column(name = "prompt_version", nullable = false, length = 80)
    private String promptVersion;

    @Column(name = "rubric_version", nullable = false, length = 80)
    private String rubricVersion;

    @Column(name = "model_name", length = 120)
    private String modelName;

    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "json")
    private String inputSnapshotJson = "{}";

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_message", length = 1200)
    private String errorMessage;

    @Column(name = "supersedes_job_id")
    private Long supersedesJobId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (status == null) {
            status = AbilityAssessmentJobStatus.PENDING;
        }
        if (fairnessStatus == null) {
            fairnessStatus = AbilityAssessmentFairnessStatus.CLEAR;
        }
        if (inputSnapshotJson == null) {
            inputSnapshotJson = "{}";
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

    public Long getAchievementRecordId() {
        return achievementRecordId;
    }

    public void setAchievementRecordId(Long achievementRecordId) {
        this.achievementRecordId = achievementRecordId;
    }

    public AbilityAssessmentJobStatus getStatus() {
        return status;
    }

    public void setStatus(AbilityAssessmentJobStatus status) {
        this.status = status;
    }

    public AbilityAssessmentFairnessStatus getFairnessStatus() {
        return fairnessStatus;
    }

    public void setFairnessStatus(AbilityAssessmentFairnessStatus fairnessStatus) {
        this.fairnessStatus = fairnessStatus;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public void setEvidenceHash(String evidenceHash) {
        this.evidenceHash = evidenceHash;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public void setContentFingerprint(String contentFingerprint) {
        this.contentFingerprint = contentFingerprint;
    }

    public Long getDuplicateOfJobId() {
        return duplicateOfJobId;
    }

    public void setDuplicateOfJobId(Long duplicateOfJobId) {
        this.duplicateOfJobId = duplicateOfJobId;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getRubricVersion() {
        return rubricVersion;
    }

    public void setRubricVersion(String rubricVersion) {
        this.rubricVersion = rubricVersion;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getInputSnapshotJson() {
        return inputSnapshotJson;
    }

    public void setInputSnapshotJson(String inputSnapshotJson) {
        this.inputSnapshotJson = inputSnapshotJson;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getSupersedesJobId() {
        return supersedesJobId;
    }

    public void setSupersedesJobId(Long supersedesJobId) {
        this.supersedesJobId = supersedesJobId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
