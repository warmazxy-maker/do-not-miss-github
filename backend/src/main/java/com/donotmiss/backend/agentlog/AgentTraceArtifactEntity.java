package com.donotmiss.backend.agentlog;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "agent_trace_artifacts",
        indexes = {
                @Index(name = "idx_agent_trace_artifacts_run", columnList = "run_id,id"),
                @Index(name = "idx_agent_trace_artifacts_run_type", columnList = "run_id,artifact_type")
        }
)
public class AgentTraceArtifactEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_name", length = 64)
    private AgentStepName stepName;

    @Column(name = "artifact_type", nullable = false, length = 80)
    private String artifactType;

    @Column(name = "content_summary", length = 1000)
    private String contentSummary;

    @Column(name = "content_json", nullable = false, columnDefinition = "json")
    private String contentJson = "{}";

    @Column(name = "content_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String contentHash;

    @Column(nullable = false)
    private boolean redacted = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (contentJson == null || contentJson.isBlank()) {
            contentJson = "{}";
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

    public AgentStepName getStepName() {
        return stepName;
    }

    public void setStepName(AgentStepName stepName) {
        this.stepName = stepName;
    }

    public String getArtifactType() {
        return artifactType;
    }

    public void setArtifactType(String artifactType) {
        this.artifactType = artifactType;
    }

    public String getContentSummary() {
        return contentSummary;
    }

    public void setContentSummary(String contentSummary) {
        this.contentSummary = contentSummary;
    }

    public String getContentJson() {
        return contentJson;
    }

    public void setContentJson(String contentJson) {
        this.contentJson = contentJson;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isRedacted() {
        return redacted;
    }

    public void setRedacted(boolean redacted) {
        this.redacted = redacted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
