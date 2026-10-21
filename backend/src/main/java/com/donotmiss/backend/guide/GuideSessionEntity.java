package com.donotmiss.backend.guide;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "guide_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_guide_sessions_session", columnNames = "session_id"),
        indexes = {
                @Index(name = "idx_guide_sessions_user_updated", columnList = "user_id,updated_at"),
                @Index(name = "idx_guide_sessions_user_expires", columnList = "user_id,expires_at")
        }
)
public class GuideSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "current_goal", length = 500)
    private String currentGoal;

    @Column(name = "session_summary", length = 3000)
    private String sessionSummary;

    @Column(name = "last_action", length = 48)
    private String lastAction;

    @Column(name = "last_rag_need", length = 1000)
    private String lastRagNeed;

    @Column(name = "recent_messages_json", nullable = false, columnDefinition = "longtext")
    private String recentMessagesJson = "[]";

    @Column(name = "last_recommended_event_ids_json", nullable = false, columnDefinition = "longtext")
    private String lastRecommendedEventIdsJson = "[]";

    @Column(name = "accepted_preferences_json", nullable = false, columnDefinition = "longtext")
    private String acceptedPreferencesJson = "[]";

    @Column(name = "rejected_conditions_json", nullable = false, columnDefinition = "longtext")
    private String rejectedConditionsJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (recentMessagesJson == null) {
            recentMessagesJson = "[]";
        }
        if (lastRecommendedEventIdsJson == null) {
            lastRecommendedEventIdsJson = "[]";
        }
        if (acceptedPreferencesJson == null) {
            acceptedPreferencesJson = "[]";
        }
        if (rejectedConditionsJson == null) {
            rejectedConditionsJson = "[]";
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (expiresAt == null) {
            expiresAt = now.plusSeconds(60 * 60);
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public void setCurrentGoal(String currentGoal) {
        this.currentGoal = currentGoal;
    }

    public String getSessionSummary() {
        return sessionSummary;
    }

    public void setSessionSummary(String sessionSummary) {
        this.sessionSummary = sessionSummary;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public String getLastRagNeed() {
        return lastRagNeed;
    }

    public void setLastRagNeed(String lastRagNeed) {
        this.lastRagNeed = lastRagNeed;
    }

    public String getRecentMessagesJson() {
        return recentMessagesJson;
    }

    public void setRecentMessagesJson(String recentMessagesJson) {
        this.recentMessagesJson = recentMessagesJson;
    }

    public String getLastRecommendedEventIdsJson() {
        return lastRecommendedEventIdsJson;
    }

    public void setLastRecommendedEventIdsJson(String lastRecommendedEventIdsJson) {
        this.lastRecommendedEventIdsJson = lastRecommendedEventIdsJson;
    }

    public String getAcceptedPreferencesJson() {
        return acceptedPreferencesJson;
    }

    public void setAcceptedPreferencesJson(String acceptedPreferencesJson) {
        this.acceptedPreferencesJson = acceptedPreferencesJson;
    }

    public String getRejectedConditionsJson() {
        return rejectedConditionsJson;
    }

    public void setRejectedConditionsJson(String rejectedConditionsJson) {
        this.rejectedConditionsJson = rejectedConditionsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
