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

/**
 * One step towards a goal. Always a platform thing, an event or a challenge (F3 decision 3): the
 * system is built around activities, and progress then follows real facts instead of ticks.
 */
@Entity
@Table(name = "tandem_goal_milestones")
public class TandemGoalMilestoneEntity {
    public enum Kind {
        EVENT("活动"),
        CHALLENGE("挑战");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goal_id", nullable = false)
    private Long goalId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 200)
    private String note;

    @Column(name = "created_challenge", nullable = false)
    private boolean createdChallenge;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static TandemGoalMilestoneEntity of(Long goalId, String userId, int position, Kind kind, Long refId,
                                               String title, String note, boolean createdChallenge) {
        TandemGoalMilestoneEntity milestone = new TandemGoalMilestoneEntity();
        milestone.goalId = goalId;
        milestone.userId = userId;
        milestone.position = position;
        milestone.kind = kind;
        milestone.refId = refId;
        milestone.title = title.length() <= 160 ? title : title.substring(0, 160);
        milestone.note = note;
        milestone.createdChallenge = createdChallenge;
        milestone.createdAt = Instant.now();
        return milestone;
    }

    public Long getId() {
        return id;
    }

    public Long getGoalId() {
        return goalId;
    }

    public String getUserId() {
        return userId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Kind getKind() {
        return kind;
    }

    public Long getRefId() {
        return refId;
    }

    public String getTitle() {
        return title;
    }

    public String getNote() {
        return note;
    }

    public boolean isCreatedChallenge() {
        return createdChallenge;
    }
}
