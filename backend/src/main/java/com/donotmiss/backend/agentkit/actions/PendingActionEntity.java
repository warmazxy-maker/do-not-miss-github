package com.donotmiss.backend.agentkit.actions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A confirmation card and everything needed to run it, and later roll it back (F2). */
@Entity
@Table(name = "agent_pending_actions")
public class PendingActionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "agent_name", nullable = false, length = 40)
    private String agentName;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "tool_version", nullable = false)
    private int toolVersion;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "card_json", nullable = false, columnDefinition = "TEXT")
    private String cardJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PendingActionStatus status;

    @Column(name = "result_message", length = 500)
    private String resultMessage;

    @Column(name = "undo_json", columnDefinition = "TEXT")
    private String undoJson;

    @Column(name = "replaced_by")
    private Long replacedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "undone_at")
    private Instant undoneAt;

    public static PendingActionEntity proposed(String userId, String agentName, Long runId, String toolName,
                                               int toolVersion, String payloadJson, String cardJson,
                                               Instant now, Instant expiresAt) {
        PendingActionEntity entity = new PendingActionEntity();
        entity.userId = userId;
        entity.agentName = agentName;
        entity.runId = runId;
        entity.toolName = toolName;
        entity.toolVersion = toolVersion;
        entity.payloadJson = payloadJson;
        entity.cardJson = cardJson;
        entity.status = PendingActionStatus.PENDING;
        entity.createdAt = now;
        entity.expiresAt = expiresAt;
        return entity;
    }

    /** True when it still says PENDING but its time is up; the stored status catches up lazily. */
    public boolean overdue(Instant now) {
        return status == PendingActionStatus.PENDING && !now.isBefore(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getAgentName() {
        return agentName;
    }

    public Long getRunId() {
        return runId;
    }

    public String getToolName() {
        return toolName;
    }

    public int getToolVersion() {
        return toolVersion;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getCardJson() {
        return cardJson;
    }

    public PendingActionStatus getStatus() {
        return status;
    }

    public void setStatus(PendingActionStatus status) {
        this.status = status;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public void setResultMessage(String resultMessage) {
        this.resultMessage = resultMessage == null || resultMessage.length() <= 500
                ? resultMessage : resultMessage.substring(0, 499) + "…";
    }

    public String getUndoJson() {
        return undoJson;
    }

    public void setUndoJson(String undoJson) {
        this.undoJson = undoJson;
    }

    public Long getReplacedBy() {
        return replacedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public Instant getUndoneAt() {
        return undoneAt;
    }

    public void setUndoneAt(Instant undoneAt) {
        this.undoneAt = undoneAt;
    }
}
