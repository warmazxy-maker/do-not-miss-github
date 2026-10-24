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

/** A reaction attached to one impression. At most one row per (impression, type). */
@Entity
@Table(
        name = "recommendation_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_rec_feedback_impression_type", columnNames = {"impression_id", "feedback_type"}),
        indexes = {
                @Index(name = "idx_rec_feedback_user_explicit", columnList = "user_id,explicit_feedback,event_id"),
                @Index(name = "idx_rec_feedback_created", columnList = "created_at")
        }
)
public class RecommendationFeedbackEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "impression_id", nullable = false)
    private Long impressionId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 32)
    private RecommendationFeedbackType feedbackType;

    @Column(name = "explicit_feedback", nullable = false)
    private boolean explicitFeedback;

    @Column(length = 500)
    private String reason;

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

    public static RecommendationFeedbackEntity of(RecommendationImpressionEntity impression,
                                                  RecommendationFeedbackType type,
                                                  String reason) {
        RecommendationFeedbackEntity feedback = new RecommendationFeedbackEntity();
        feedback.impressionId = impression.getId();
        feedback.userId = impression.getUserId();
        feedback.eventId = impression.getEventId();
        feedback.feedbackType = type;
        feedback.explicitFeedback = type.explicit();
        feedback.reason = reason;
        return feedback;
    }

    public Long getId() {
        return id;
    }

    public Long getImpressionId() {
        return impressionId;
    }

    public String getUserId() {
        return userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public RecommendationFeedbackType getFeedbackType() {
        return feedbackType;
    }

    public boolean isExplicitFeedback() {
        return explicitFeedback;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
