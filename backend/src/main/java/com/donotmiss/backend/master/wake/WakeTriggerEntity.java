package com.donotmiss.backend.master.wake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One thing worth telling a student, found by a scanner (F4). Said at most once (dedupe key). */
@Entity
@Table(name = "tandem_wake_triggers")
public class WakeTriggerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private WakeKind kind;

    @Column(name = "dedupe_key", nullable = false, length = 160)
    private String dedupeKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WakeTriggerStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "process_after", nullable = false)
    private Instant processAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "run_id")
    private Long runId;

    @Column(length = 300)
    private String note;

    public static WakeTriggerEntity create(String userId, WakeKind kind, String dedupeKey, String payloadJson,
                                           Instant now, Instant processAfter) {
        WakeTriggerEntity trigger = new WakeTriggerEntity();
        trigger.userId = userId;
        trigger.kind = kind;
        trigger.dedupeKey = dedupeKey.length() <= 160 ? dedupeKey : dedupeKey.substring(0, 160);
        trigger.payloadJson = payloadJson;
        trigger.status = WakeTriggerStatus.PENDING;
        trigger.createdAt = now;
        trigger.processAfter = processAfter;
        return trigger;
    }

    public void finish(WakeTriggerStatus status, String note, Long messageId, Long runId, Instant now) {
        this.status = status;
        this.note = note == null || note.length() <= 300 ? note : note.substring(0, 300);
        this.messageId = messageId;
        this.runId = runId;
        this.processedAt = now;
    }

    /** Back to waiting, e.g. after a model failure or when today's limit is reached. */
    public void retryAt(Instant when, String note) {
        this.status = WakeTriggerStatus.PENDING;
        this.processAfter = when;
        this.note = note;
    }

    public void countAttempt() {
        attempts++;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public WakeKind getKind() {
        return kind;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public WakeTriggerStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getProcessAfter() {
        return processAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getMessageId() {
        return messageId;
    }

    public String getNote() {
        return note;
    }
}
