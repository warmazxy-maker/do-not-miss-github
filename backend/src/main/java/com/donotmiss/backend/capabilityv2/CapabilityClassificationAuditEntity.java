package com.donotmiss.backend.capabilityv2;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "capability_v2_classification_audits",
        uniqueConstraints = @UniqueConstraint(name = "uk_capability_v2_audit_request", columnNames = "request_id"),
        indexes = {
                @Index(name = "idx_capability_v2_audit_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_capability_v2_audit_source", columnList = "source_type,source_id"),
                @Index(name = "idx_capability_v2_audit_status", columnList = "status,created_at")
        }
)
public class CapabilityClassificationAuditEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;
    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;
    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;
    @Column(name = "source_id", nullable = false, length = 120)
    private String sourceId;
    @Column(name = "evidence_hash", length = 64, columnDefinition = "char(64)")
    private String evidenceHash;
    @Column(name = "schema_version", nullable = false, length = 64)
    private String schemaVersion;
    @Column(name = "prompt_version", length = 80)
    private String promptVersion;
    @Column(name = "model_name", length = 120)
    private String modelName;
    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "json")
    private String inputSnapshotJson = "{}";
    @Column(name = "knowledge_candidates_json", nullable = false, columnDefinition = "json")
    private String knowledgeCandidatesJson = "[]";
    @Column(name = "function_candidates_json", nullable = false, columnDefinition = "json")
    private String functionCandidatesJson = "[]";
    @Column(name = "output_snapshot_json", columnDefinition = "json")
    private String outputSnapshotJson;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CapabilityClassificationStatus status = CapabilityClassificationStatus.PENDING;
    @Column(name = "error_message", length = 1200)
    private String errorMessage;
    @Column(name = "agent_run_id")
    private Long agentRunId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (inputSnapshotJson == null || inputSnapshotJson.isBlank()) inputSnapshotJson = "{}";
        if (knowledgeCandidatesJson == null || knowledgeCandidatesJson.isBlank()) knowledgeCandidatesJson = "[]";
        if (functionCandidatesJson == null || functionCandidatesJson.isBlank()) functionCandidatesJson = "[]";
        if (status == null) status = CapabilityClassificationStatus.PENDING;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getEvidenceHash() { return evidenceHash; }
    public void setEvidenceHash(String evidenceHash) { this.evidenceHash = evidenceHash; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getInputSnapshotJson() { return inputSnapshotJson; }
    public void setInputSnapshotJson(String inputSnapshotJson) { this.inputSnapshotJson = inputSnapshotJson; }
    public String getKnowledgeCandidatesJson() { return knowledgeCandidatesJson; }
    public void setKnowledgeCandidatesJson(String knowledgeCandidatesJson) { this.knowledgeCandidatesJson = knowledgeCandidatesJson; }
    public String getFunctionCandidatesJson() { return functionCandidatesJson; }
    public void setFunctionCandidatesJson(String functionCandidatesJson) { this.functionCandidatesJson = functionCandidatesJson; }
    public String getOutputSnapshotJson() { return outputSnapshotJson; }
    public void setOutputSnapshotJson(String outputSnapshotJson) { this.outputSnapshotJson = outputSnapshotJson; }
    public CapabilityClassificationStatus getStatus() { return status; }
    public void setStatus(CapabilityClassificationStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getAgentRunId() { return agentRunId; }
    public void setAgentRunId(Long agentRunId) { this.agentRunId = agentRunId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
