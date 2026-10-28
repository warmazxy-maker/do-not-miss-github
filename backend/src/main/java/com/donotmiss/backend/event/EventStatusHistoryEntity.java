package com.donotmiss.backend.event;

import jakarta.persistence.*;

import java.time.Instant;

/** One row per transition: the business timeline of an event. Append-only. */
@Entity
@Table(name = "event_status_history")
public class EventStatusHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private EventReviewStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EventReviewStatus toStatus;

    /** An {@link EventLifecycleAction} name, or CREATE. */
    @Column(nullable = false, length = 32)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventActorType actorType;

    @Column(length = 80)
    private String actorUserId;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected EventStatusHistoryEntity() {
    }

    static EventStatusHistoryEntity of(Long eventId,
                                       EventReviewStatus fromStatus,
                                       EventReviewStatus toStatus,
                                       String action,
                                       EventActor actor,
                                       String reason) {
        EventStatusHistoryEntity row = new EventStatusHistoryEntity();
        row.eventId = eventId;
        row.fromStatus = fromStatus;
        row.toStatus = toStatus;
        row.action = action;
        row.actorType = actor.type();
        row.actorUserId = actor.userId();
        row.reason = reason;
        row.createdAt = Instant.now();
        return row;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public EventReviewStatus getFromStatus() {
        return fromStatus;
    }

    public EventReviewStatus getToStatus() {
        return toStatus;
    }

    public String getAction() {
        return action;
    }

    public EventActorType getActorType() {
        return actorType;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
