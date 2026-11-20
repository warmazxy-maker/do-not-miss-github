package com.donotmiss.backend.master.goal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** F3: where the student wants to go, in their own words (F3 decisions 1 and 2). */
@Entity
@Table(name = "tandem_goals")
public class TandemGoalEntity {
    public enum Type {
        OUTCOME("结果型"),
        ABILITY("能力型"),
        HABIT("习惯型");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Status {
        ACTIVE("进行中"),
        COMPLETED("已完成"),
        ABANDONED("已放弃");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type", nullable = false, length = 16)
    private Type goalType;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "last_review_note", length = 300)
    private String lastReviewNote;

    @Column(name = "source_run_id")
    private Long sourceRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public static TandemGoalEntity create(String userId, Long sourceRunId) {
        TandemGoalEntity goal = new TandemGoalEntity();
        goal.userId = userId;
        goal.sourceRunId = sourceRunId;
        goal.status = Status.ACTIVE;
        goal.createdAt = Instant.now();
        goal.updatedAt = goal.createdAt;
        return goal;
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public void setStatus(Status status) {
        if (this.status != status) {
            closedAt = status == Status.ACTIVE ? null : Instant.now();
        }
        this.status = status;
        touch();
    }

    public void review(String note) {
        lastReviewedAt = Instant.now();
        lastReviewNote = note == null || note.length() <= 300 ? note : note.substring(0, 300);
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Type getGoalType() {
        return goalType;
    }

    public void setGoalType(Type goalType) {
        this.goalType = goalType;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getLastReviewedAt() {
        return lastReviewedAt;
    }

    public String getLastReviewNote() {
        return lastReviewNote;
    }

    public Long getSourceRunId() {
        return sourceRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
