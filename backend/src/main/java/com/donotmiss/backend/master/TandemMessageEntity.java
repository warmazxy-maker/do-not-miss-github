package com.donotmiss.backend.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One message of a student's single Tandem conversation (F1). */
@Entity
@Table(name = "tandem_messages")
public class TandemMessageEntity {
    public static final String USER = "USER";
    public static final String ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "longtext")
    private String content;

    /** Assistant only: the steps the student saw, as JSON. Never raw tool data. */
    @Column(name = "steps_json", columnDefinition = "longtext")
    private String stepsJson;

    @Column(name = "notices_json", columnDefinition = "longtext")
    private String noticesJson;

    @Column(nullable = false)
    private boolean degraded;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** F4: Tandem wrote this on its own, woken by something that happened on the platform. */
    @Column(nullable = false)
    private boolean proactive;

    /** F4: when the student first saw a proactive message; null = unread. */
    @Column(name = "read_at")
    private Instant readAt;

    /** F4: what the model is told about this message later (why it was sent, ids involved). */
    @Column(name = "context_note", length = 1000)
    private String contextNote;

    protected TandemMessageEntity() {
    }

    public static TandemMessageEntity user(String userId, String content) {
        TandemMessageEntity message = new TandemMessageEntity();
        message.userId = userId;
        message.role = USER;
        message.content = content;
        message.createdAt = Instant.now();
        return message;
    }

    public static TandemMessageEntity assistant(String userId, String content, String stepsJson, String noticesJson,
                                                boolean degraded, Long runId) {
        TandemMessageEntity message = new TandemMessageEntity();
        message.userId = userId;
        message.role = ASSISTANT;
        message.content = content;
        message.stepsJson = stepsJson;
        message.noticesJson = noticesJson;
        message.degraded = degraded;
        message.runId = runId;
        message.createdAt = Instant.now();
        return message;
    }

    /** F4: a message Tandem leaves on its own, in the same conversation (F4 decision 1). */
    public static TandemMessageEntity proactive(String userId, String content, String contextNote, Long runId) {
        TandemMessageEntity message = assistant(userId, content, "[]", "[]", false, runId);
        message.proactive = true;
        message.contextNote = contextNote == null || contextNote.length() <= 1000 ? contextNote : contextNote.substring(0, 1000);
        return message;
    }

    public boolean isProactive() {
        return proactive;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public String getContextNote() {
        return contextNote;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public boolean isUser() {
        return USER.equals(role);
    }

    public String getContent() {
        return content;
    }

    public String getStepsJson() {
        return stepsJson;
    }

    public String getNoticesJson() {
        return noticesJson;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public Long getRunId() {
        return runId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
