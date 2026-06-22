package com.donotmiss.backend.coach;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "coach_memory_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_coach_memory_review_log", columnNames = {"user_id", "source_log_id"}),
        indexes = {
                @Index(name = "idx_coach_memory_reviews_due", columnList = "user_id,next_review_at"),
                @Index(name = "idx_coach_memory_reviews_updated", columnList = "user_id,updated_at")
        }
)
public class CoachMemoryReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "source_log_id", nullable = false)
    private Long sourceLogId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type", nullable = false, length = 32)
    private CoachMemoryType memoryType;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "memory_text", nullable = false, length = 1200)
    private String memoryText;

    @Column(length = 500)
    private String tags;

    @Column(nullable = false)
    private int strength;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "next_review_at", nullable = false)
    private Instant nextReviewAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
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
        if (nextReviewAt == null) {
            nextReviewAt = now;
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

    public Long getSourceLogId() {
        return sourceLogId;
    }

    public void setSourceLogId(Long sourceLogId) {
        this.sourceLogId = sourceLogId;
    }

    public CoachMemoryType getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(CoachMemoryType memoryType) {
        this.memoryType = memoryType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMemoryText() {
        return memoryText;
    }

    public void setMemoryText(String memoryText) {
        this.memoryText = memoryText;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public int getStrength() {
        return strength;
    }

    public void setStrength(int strength) {
        this.strength = strength;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(int reviewCount) {
        this.reviewCount = reviewCount;
    }

    public Instant getLastReviewedAt() {
        return lastReviewedAt;
    }

    public void setLastReviewedAt(Instant lastReviewedAt) {
        this.lastReviewedAt = lastReviewedAt;
    }

    public Instant getNextReviewAt() {
        return nextReviewAt;
    }

    public void setNextReviewAt(Instant nextReviewAt) {
        this.nextReviewAt = nextReviewAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
