package com.donotmiss.backend.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** One recommended event shown to one student in one recommendation run. */
@Entity
@Table(
        name = "recommendation_impressions",
        indexes = {
                @Index(name = "idx_rec_impression_user_event", columnList = "user_id,event_id,shown_at"),
                @Index(name = "idx_rec_impression_run", columnList = "run_id"),
                @Index(name = "idx_rec_impression_shown", columnList = "shown_at")
        }
)
public class RecommendationImpressionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(nullable = false)
    private int score;

    @Column(name = "need_text", length = 500)
    private String needText;

    @Column(name = "shown_at", nullable = false)
    private Instant shownAt;

    @PrePersist
    void prePersist() {
        if (shownAt == null) {
            shownAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public int getRankNo() {
        return rankNo;
    }

    public void setRankNo(int rankNo) {
        this.rankNo = rankNo;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getNeedText() {
        return needText;
    }

    public void setNeedText(String needText) {
        this.needText = needText;
    }

    public Instant getShownAt() {
        return shownAt;
    }

    public void setShownAt(Instant shownAt) {
        this.shownAt = shownAt;
    }
}
