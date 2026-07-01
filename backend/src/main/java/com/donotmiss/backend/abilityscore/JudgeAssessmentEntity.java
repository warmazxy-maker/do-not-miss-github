package com.donotmiss.backend.abilityscore;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "judge_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uk_judge_assessment_request", columnNames = "request_id"),
        indexes = {
                @Index(name = "idx_judge_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_judge_status_created", columnList = "status,created_at"),
                @Index(name = "idx_judge_state_created", columnList = "ability_state_id,created_at")
        }
)
public class JudgeAssessmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "ability_state_id", nullable = false)
    private Long abilityStateId;

    @Column(name = "score_result_id")
    private Long scoreResultId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JudgeAssessmentStatus status = JudgeAssessmentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JudgeDecision decision = JudgeDecision.PENDING;

    @Column(name = "trigger_reasons_json", nullable = false, columnDefinition = "json")
    private String triggerReasonsJson = "[]";

    @Column(name = "questions_json", nullable = false, columnDefinition = "json")
    private String questionsJson = "[]";

    @Column(name = "answers_json", nullable = false, columnDefinition = "json")
    private String answersJson = "[]";

    @Column(name = "rubric_result_json", nullable = false, columnDefinition = "json")
    private String rubricResultJson = "{}";

    @Column(name = "rubric_version", nullable = false, length = 80)
    private String rubricVersion;

    @Column(name = "judge_model_name", length = 120)
    private String judgeModelName;

    @Column(name = "ability_score_at_trigger", nullable = false, precision = 7, scale = 4)
    private BigDecimal abilityScoreAtTrigger;

    @Column(name = "confidence_before", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceBefore;

    @Column(name = "proposed_confidence_delta", nullable = false, precision = 5, scale = 4)
    private BigDecimal proposedConfidenceDelta = BigDecimal.ZERO;

    @Column(name = "confidence_after", precision = 5, scale = 4)
    private BigDecimal confidenceAfter;

    @Column(name = "reviewer_type", length = 32)
    private String reviewerType;

    @Column(name = "reviewer_id", length = 80)
    private String reviewerId;

    @Column(name = "review_reason", length = 1000)
    private String reviewReason;

    @Version
    @Column(name = "judge_version", nullable = false)
    private long judgeVersion;

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
            status = JudgeAssessmentStatus.PENDING;
        }
        if (decision == null) {
            decision = JudgeDecision.PENDING;
        }
        if (triggerReasonsJson == null) {
            triggerReasonsJson = "[]";
        }
        if (questionsJson == null) {
            questionsJson = "[]";
        }
        if (answersJson == null) {
            answersJson = "[]";
        }
        if (rubricResultJson == null) {
            rubricResultJson = "{}";
        }
        if (proposedConfidenceDelta == null) {
            proposedConfidenceDelta = BigDecimal.ZERO;
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

    public Long getAbilityStateId() {
        return abilityStateId;
    }

    public void setAbilityStateId(Long abilityStateId) {
        this.abilityStateId = abilityStateId;
    }

    public Long getScoreResultId() {
        return scoreResultId;
    }

    public void setScoreResultId(Long scoreResultId) {
        this.scoreResultId = scoreResultId;
    }

    public JudgeAssessmentStatus getStatus() {
        return status;
    }

    public void setStatus(JudgeAssessmentStatus status) {
        this.status = status;
    }

    public JudgeDecision getDecision() {
        return decision;
    }

    public void setDecision(JudgeDecision decision) {
        this.decision = decision;
    }

    public String getTriggerReasonsJson() {
        return triggerReasonsJson;
    }

    public void setTriggerReasonsJson(String triggerReasonsJson) {
        this.triggerReasonsJson = triggerReasonsJson;
    }

    public String getQuestionsJson() {
        return questionsJson;
    }

    public void setQuestionsJson(String questionsJson) {
        this.questionsJson = questionsJson;
    }

    public String getAnswersJson() {
        return answersJson;
    }

    public void setAnswersJson(String answersJson) {
        this.answersJson = answersJson;
    }

    public String getRubricResultJson() {
        return rubricResultJson;
    }

    public void setRubricResultJson(String rubricResultJson) {
        this.rubricResultJson = rubricResultJson;
    }

    public String getRubricVersion() {
        return rubricVersion;
    }

    public void setRubricVersion(String rubricVersion) {
        this.rubricVersion = rubricVersion;
    }

    public String getJudgeModelName() {
        return judgeModelName;
    }

    public void setJudgeModelName(String judgeModelName) {
        this.judgeModelName = judgeModelName;
    }

    public BigDecimal getAbilityScoreAtTrigger() {
        return abilityScoreAtTrigger;
    }

    public void setAbilityScoreAtTrigger(BigDecimal abilityScoreAtTrigger) {
        this.abilityScoreAtTrigger = abilityScoreAtTrigger;
    }

    public BigDecimal getConfidenceBefore() {
        return confidenceBefore;
    }

    public void setConfidenceBefore(BigDecimal confidenceBefore) {
        this.confidenceBefore = confidenceBefore;
    }

    public BigDecimal getProposedConfidenceDelta() {
        return proposedConfidenceDelta;
    }

    public void setProposedConfidenceDelta(BigDecimal proposedConfidenceDelta) {
        this.proposedConfidenceDelta = proposedConfidenceDelta;
    }

    public BigDecimal getConfidenceAfter() {
        return confidenceAfter;
    }

    public void setConfidenceAfter(BigDecimal confidenceAfter) {
        this.confidenceAfter = confidenceAfter;
    }

    public String getReviewerType() {
        return reviewerType;
    }

    public void setReviewerType(String reviewerType) {
        this.reviewerType = reviewerType;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public long getJudgeVersion() {
        return judgeVersion;
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
