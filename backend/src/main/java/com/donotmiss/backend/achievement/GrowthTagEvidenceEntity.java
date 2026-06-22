package com.donotmiss.backend.achievement;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "growth_tag_evidences",
        uniqueConstraints = @UniqueConstraint(name = "uk_growth_tag_evidence_record", columnNames = {"tag_id", "record_id"}),
        indexes = {
                @Index(name = "idx_growth_tag_evidences_user_time", columnList = "user_id,occurred_at"),
                @Index(name = "idx_growth_tag_evidences_tag_time", columnList = "tag_id,occurred_at")
        }
)
public class GrowthTagEvidenceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false)
    private GrowthTagEntity tag;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false)
    private AchievementRecordEntity record;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private AchievementSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 800)
    private String summary;

    @Column(length = 1200)
    private String did;

    @Column(length = 1200)
    private String learned;

    @Column(name = "score_delta", nullable = false)
    private int scoreDelta;

    @Column(name = "is_milestone", nullable = false)
    private boolean milestone;

    @Column(name = "milestone_reason", length = 500)
    private String milestoneReason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (occurredAt == null) {
            occurredAt = now;
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

    public GrowthTagEntity getTag() {
        return tag;
    }

    public void setTag(GrowthTagEntity tag) {
        this.tag = tag;
    }

    public AchievementRecordEntity getRecord() {
        return record;
    }

    public void setRecord(AchievementRecordEntity record) {
        this.record = record;
    }

    public AchievementSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(AchievementSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDid() {
        return did;
    }

    public void setDid(String did) {
        this.did = did;
    }

    public String getLearned() {
        return learned;
    }

    public void setLearned(String learned) {
        this.learned = learned;
    }

    public int getScoreDelta() {
        return scoreDelta;
    }

    public void setScoreDelta(int scoreDelta) {
        this.scoreDelta = scoreDelta;
    }

    public boolean isMilestone() {
        return milestone;
    }

    public void setMilestone(boolean milestone) {
        this.milestone = milestone;
    }

    public String getMilestoneReason() {
        return milestoneReason;
    }

    public void setMilestoneReason(String milestoneReason) {
        this.milestoneReason = milestoneReason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
