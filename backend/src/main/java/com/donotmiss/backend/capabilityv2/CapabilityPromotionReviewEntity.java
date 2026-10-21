package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.JudgeDecision;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "capability_v2_promotion_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_capability_v2_promotion_review_open", columnNames = "open_key"),
        indexes = @Index(name = "idx_capability_v2_review_user_bucket", columnList = "user_id,bucket_id,created_at")
)
public class CapabilityPromotionReviewEntity {
    public enum Status { IN_PROGRESS, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;
    @Column(name = "bucket_id", nullable = false)
    private Long bucketId;
    @Column(name = "user_state_id")
    private Long userStateId;
    @Column(name = "trigger_evidence_link_id")
    private Long triggerEvidenceLinkId;
    @Column(name = "from_rank", nullable = false, length = 48)
    private String fromRank;
    @Column(name = "target_rank", nullable = false, length = 48)
    private String targetRank;
    @Column(name = "ability_score_at_start", nullable = false, precision = 7, scale = 4)
    private BigDecimal abilityScoreAtStart;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status = Status.IN_PROGRESS;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JudgeDecision decision = JudgeDecision.PENDING;
    @Column(name = "model_evaluated", nullable = false)
    private boolean modelEvaluated;
    @Column(name = "applied_rank", length = 48)
    private String appliedRank;
    @Column(name = "questions_json", nullable = false, columnDefinition = "json")
    private String questionsJson = "[]";
    @Column(name = "answers_json", nullable = false, columnDefinition = "json")
    private String answersJson = "[]";
    @Column(name = "rubric_json", nullable = false, columnDefinition = "json")
    private String rubricJson = "{}";
    @Column(name = "confidence_before", precision = 5, scale = 4)
    private BigDecimal confidenceBefore;
    @Column(name = "confidence_delta", precision = 5, scale = 4)
    private BigDecimal confidenceDelta;
    @Column(name = "open_key", length = 140)
    private String openKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    public static String openKeyFor(String userId, Long bucketId) {
        return userId + ":" + bucketId;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getBucketId() { return bucketId; }
    public void setBucketId(Long bucketId) { this.bucketId = bucketId; }
    public Long getUserStateId() { return userStateId; }
    public void setUserStateId(Long userStateId) { this.userStateId = userStateId; }
    public Long getTriggerEvidenceLinkId() { return triggerEvidenceLinkId; }
    public void setTriggerEvidenceLinkId(Long triggerEvidenceLinkId) { this.triggerEvidenceLinkId = triggerEvidenceLinkId; }
    public String getFromRank() { return fromRank; }
    public void setFromRank(String fromRank) { this.fromRank = fromRank; }
    public String getTargetRank() { return targetRank; }
    public void setTargetRank(String targetRank) { this.targetRank = targetRank; }
    public BigDecimal getAbilityScoreAtStart() { return abilityScoreAtStart; }
    public void setAbilityScoreAtStart(BigDecimal abilityScoreAtStart) { this.abilityScoreAtStart = abilityScoreAtStart; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public JudgeDecision getDecision() { return decision; }
    public void setDecision(JudgeDecision decision) { this.decision = decision; }
    public boolean isModelEvaluated() { return modelEvaluated; }
    public void setModelEvaluated(boolean modelEvaluated) { this.modelEvaluated = modelEvaluated; }
    public String getAppliedRank() { return appliedRank; }
    public void setAppliedRank(String appliedRank) { this.appliedRank = appliedRank; }
    public String getQuestionsJson() { return questionsJson; }
    public void setQuestionsJson(String questionsJson) { this.questionsJson = questionsJson; }
    public String getAnswersJson() { return answersJson; }
    public void setAnswersJson(String answersJson) { this.answersJson = answersJson; }
    public String getRubricJson() { return rubricJson; }
    public void setRubricJson(String rubricJson) { this.rubricJson = rubricJson; }
    public BigDecimal getConfidenceBefore() { return confidenceBefore; }
    public void setConfidenceBefore(BigDecimal confidenceBefore) { this.confidenceBefore = confidenceBefore; }
    public BigDecimal getConfidenceDelta() { return confidenceDelta; }
    public void setConfidenceDelta(BigDecimal confidenceDelta) { this.confidenceDelta = confidenceDelta; }
    public String getOpenKey() { return openKey; }
    public void setOpenKey(String openKey) { this.openKey = openKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
