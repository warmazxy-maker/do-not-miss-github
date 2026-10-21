package com.donotmiss.backend.capabilityv2;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "capability_v2_evidence_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_capability_v2_evidence_bucket_key",
                columnNames = {"bucket_id", "idempotency_key"}
        ),
        indexes = {
                @Index(name = "idx_capability_v2_evidence_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_capability_v2_evidence_user_bucket", columnList = "user_id,bucket_id,created_at"),
                @Index(name = "idx_capability_v2_evidence_source", columnList = "source_type,source_id"),
                @Index(name = "idx_capability_v2_evidence_status", columnList = "status,created_at")
        }
)
public class CapabilityEvidenceLinkEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;
    @Column(name = "bucket_id", nullable = false)
    private Long bucketId;
    @Column(name = "user_state_id")
    private Long userStateId;
    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;
    @Column(name = "source_id", nullable = false, length = 120)
    private String sourceId;
    @Column(name = "evidence_hash", length = 64, columnDefinition = "char(64)")
    private String evidenceHash;
    @Column(name = "legacy_assessment_id")
    private Long legacyAssessmentId;
    @Column(name = "legacy_dimension_id")
    private Long legacyDimensionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 24)
    private CapabilityEvidenceRole roleName = CapabilityEvidenceRole.NONE;
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal relevance;
    @Column(name = "relevance_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal relevanceConfidence;
    @Column(name = "claimed_outcome", length = 1200)
    private String claimedOutcome;
    @Column(name = "evidence_findings_json", nullable = false, columnDefinition = "json")
    private String evidenceFindingsJson = "[]";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CapabilityEvidenceLinkStatus status = CapabilityEvidenceLinkStatus.PENDING;
    @Column(name = "idempotency_key", nullable = false, length = 220)
    private String idempotencyKey;
    @Column(name = "agent_run_id")
    private Long agentRunId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (roleName == null) roleName = CapabilityEvidenceRole.NONE;
        if (status == null) status = CapabilityEvidenceLinkStatus.PENDING;
        if (evidenceFindingsJson == null || evidenceFindingsJson.isBlank()) evidenceFindingsJson = "[]";
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getBucketId() { return bucketId; }
    public void setBucketId(Long bucketId) { this.bucketId = bucketId; }
    public Long getUserStateId() { return userStateId; }
    public void setUserStateId(Long userStateId) { this.userStateId = userStateId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getEvidenceHash() { return evidenceHash; }
    public void setEvidenceHash(String evidenceHash) { this.evidenceHash = evidenceHash; }
    public Long getLegacyAssessmentId() { return legacyAssessmentId; }
    public void setLegacyAssessmentId(Long legacyAssessmentId) { this.legacyAssessmentId = legacyAssessmentId; }
    public Long getLegacyDimensionId() { return legacyDimensionId; }
    public void setLegacyDimensionId(Long legacyDimensionId) { this.legacyDimensionId = legacyDimensionId; }
    public CapabilityEvidenceRole getRoleName() { return roleName; }
    public void setRoleName(CapabilityEvidenceRole roleName) { this.roleName = roleName; }
    public BigDecimal getRelevance() { return relevance; }
    public void setRelevance(BigDecimal relevance) { this.relevance = relevance; }
    public BigDecimal getRelevanceConfidence() { return relevanceConfidence; }
    public void setRelevanceConfidence(BigDecimal relevanceConfidence) { this.relevanceConfidence = relevanceConfidence; }
    public String getClaimedOutcome() { return claimedOutcome; }
    public void setClaimedOutcome(String claimedOutcome) { this.claimedOutcome = claimedOutcome; }
    public String getEvidenceFindingsJson() { return evidenceFindingsJson; }
    public void setEvidenceFindingsJson(String evidenceFindingsJson) { this.evidenceFindingsJson = evidenceFindingsJson; }
    public CapabilityEvidenceLinkStatus getStatus() { return status; }
    public void setStatus(CapabilityEvidenceLinkStatus status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Long getAgentRunId() { return agentRunId; }
    public void setAgentRunId(Long agentRunId) { this.agentRunId = agentRunId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
