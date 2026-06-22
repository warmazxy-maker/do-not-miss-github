package com.donotmiss.backend.mq;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "domain_event_outbox",
        indexes = {
                @Index(name = "idx_domain_event_outbox_status_next", columnList = "status,next_attempt_at,id"),
                @Index(name = "idx_domain_event_outbox_created", columnList = "created_at")
        }
)
public class DomainEventOutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_name", nullable = false, length = 160)
    private String exchangeName;

    @Column(name = "routing_key", nullable = false, length = 160)
    private String routingKey;

    @Column(name = "payload_type", nullable = false)
    private String payloadType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "json")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DomainEventOutboxStatus status = DomainEventOutboxStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (status == null) {
            status = DomainEventOutboxStatus.PENDING;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public static DomainEventOutboxEntity pending(String exchangeName, String routingKey, Object payload, String payloadJson) {
        DomainEventOutboxEntity event = new DomainEventOutboxEntity();
        event.setExchangeName(exchangeName);
        event.setRoutingKey(routingKey);
        event.setPayloadType(payload.getClass().getName());
        event.setPayloadJson(payloadJson);
        event.setStatus(DomainEventOutboxStatus.PENDING);
        event.setNextAttemptAt(Instant.now());
        return event;
    }

    public Long getId() {
        return id;
    }

    public String getExchangeName() {
        return exchangeName;
    }

    public void setExchangeName(String exchangeName) {
        this.exchangeName = exchangeName;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(String payloadType) {
        this.payloadType = payloadType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public DomainEventOutboxStatus getStatus() {
        return status;
    }

    public void setStatus(DomainEventOutboxStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
