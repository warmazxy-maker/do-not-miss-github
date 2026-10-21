package com.donotmiss.backend.resumeinterview;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "resume_interview_score_summaries",
        uniqueConstraints = @UniqueConstraint(name = "uk_resume_score_session", columnNames = "session_id"),
        indexes = @Index(name = "idx_resume_score_status_updated", columnList = "status,updated_at")
)
public class ResumeInterviewScoreSummaryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResumeInterviewScoreStatus status = ResumeInterviewScoreStatus.DRAFT;

    @Column(name = "ability_score_result_ids_json", nullable = false, columnDefinition = "json")
    private String abilityScoreResultIdsJson = "[]";

    @Column(name = "score_summary_json", nullable = false, columnDefinition = "json")
    private String scoreSummaryJson = "{}";

    @Column(name = "judge_triggered_json", nullable = false, columnDefinition = "json")
    private String judgeTriggeredJson = "[]";

    @Column(name = "scoring_rule_version", length = 80)
    private String scoringRuleVersion;

    @Column(name = "scoring_trace_run_id")
    private Long scoringTraceRunId;

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
            status = ResumeInterviewScoreStatus.DRAFT;
        }
        if (abilityScoreResultIdsJson == null) {
            abilityScoreResultIdsJson = "[]";
        }
        if (scoreSummaryJson == null) {
            scoreSummaryJson = "{}";
        }
        if (judgeTriggeredJson == null) {
            judgeTriggeredJson = "[]";
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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public ResumeInterviewScoreStatus getStatus() {
        return status;
    }

    public void setStatus(ResumeInterviewScoreStatus status) {
        this.status = status;
    }

    public String getAbilityScoreResultIdsJson() {
        return abilityScoreResultIdsJson;
    }

    public void setAbilityScoreResultIdsJson(String abilityScoreResultIdsJson) {
        this.abilityScoreResultIdsJson = abilityScoreResultIdsJson;
    }

    public String getScoreSummaryJson() {
        return scoreSummaryJson;
    }

    public void setScoreSummaryJson(String scoreSummaryJson) {
        this.scoreSummaryJson = scoreSummaryJson;
    }

    public String getJudgeTriggeredJson() {
        return judgeTriggeredJson;
    }

    public void setJudgeTriggeredJson(String judgeTriggeredJson) {
        this.judgeTriggeredJson = judgeTriggeredJson;
    }

    public String getScoringRuleVersion() {
        return scoringRuleVersion;
    }

    public void setScoringRuleVersion(String scoringRuleVersion) {
        this.scoringRuleVersion = scoringRuleVersion;
    }

    public Long getScoringTraceRunId() {
        return scoringTraceRunId;
    }

    public void setScoringTraceRunId(Long scoringTraceRunId) {
        this.scoringTraceRunId = scoringTraceRunId;
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
