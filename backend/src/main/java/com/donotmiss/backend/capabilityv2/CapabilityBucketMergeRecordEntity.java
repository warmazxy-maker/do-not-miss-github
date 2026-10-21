package com.donotmiss.backend.capabilityv2;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "capability_v2_bucket_merge_records",
        indexes = {
                @Index(name = "idx_capability_v2_merge_source", columnList = "source_bucket_id,created_at"),
                @Index(name = "idx_capability_v2_merge_target", columnList = "target_bucket_id,created_at")
        }
)
public class CapabilityBucketMergeRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "source_bucket_id", nullable = false)
    private Long sourceBucketId;
    @Column(name = "target_bucket_id", nullable = false)
    private Long targetBucketId;
    @Column(name = "merge_type", nullable = false, length = 32)
    private String mergeType;
    @Column(name = "decision_source", nullable = false, length = 40)
    private String decisionSource;
    @Column(length = 1200)
    private String reason;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "json")
    private String snapshotJson = "{}";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (snapshotJson == null || snapshotJson.isBlank()) snapshotJson = "{}";
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getSourceBucketId() { return sourceBucketId; }
    public void setSourceBucketId(Long sourceBucketId) { this.sourceBucketId = sourceBucketId; }
    public Long getTargetBucketId() { return targetBucketId; }
    public void setTargetBucketId(Long targetBucketId) { this.targetBucketId = targetBucketId; }
    public String getMergeType() { return mergeType; }
    public void setMergeType(String mergeType) { this.mergeType = mergeType; }
    public String getDecisionSource() { return decisionSource; }
    public void setDecisionSource(String decisionSource) { this.decisionSource = decisionSource; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Instant getCreatedAt() { return createdAt; }
}
