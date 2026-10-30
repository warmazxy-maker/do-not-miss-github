package com.donotmiss.backend.operator;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One AI-approved event drawn for a second look by an operator (D3). The event stays published
 * while it is checked. One row per event ever drawn (unique), so nothing is drawn twice.
 */
@Entity
@Table(name = "event_spot_checks")
public class EventSpotCheckEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SpotCheckStatus status;

    @Column(nullable = false, length = 80)
    private String drawnByUserId;

    @Column(nullable = false)
    private Instant drawnAt;

    @Column(length = 80)
    private String resolvedByUserId;

    private Instant resolvedAt;

    protected EventSpotCheckEntity() {
    }

    static EventSpotCheckEntity drawn(Long eventId, String operatorUserId) {
        EventSpotCheckEntity row = new EventSpotCheckEntity();
        row.eventId = eventId;
        row.status = SpotCheckStatus.PENDING;
        row.drawnByUserId = operatorUserId;
        row.drawnAt = Instant.now();
        return row;
    }

    void resolve(SpotCheckStatus outcome, String operatorUserId) {
        this.status = outcome;
        this.resolvedByUserId = operatorUserId;
        this.resolvedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public SpotCheckStatus getStatus() {
        return status;
    }

    public Instant getDrawnAt() {
        return drawnAt;
    }
}
