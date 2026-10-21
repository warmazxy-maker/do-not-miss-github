package com.donotmiss.backend.capabilityv2;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "capability_v2_buckets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_capability_v2_bucket_scope_name",
                columnNames = {"bucket_type", "owner_user_id", "normalized_name"}
        ),
        indexes = {
                @Index(name = "idx_capability_v2_bucket_type_status", columnList = "bucket_type,status,display_name"),
                @Index(name = "idx_capability_v2_bucket_domain", columnList = "domain_name,group_name"),
                @Index(name = "idx_capability_v2_bucket_owner_type_status", columnList = "owner_user_id,bucket_type,status,display_name")
        }
)
public class CapabilityBucketEntity {
    public static final String GLOBAL_OWNER = "__GLOBAL__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "bucket_type", nullable = false, length = 24)
    private CapabilityBucketType bucketType;

    /** Global FUNCTION buckets use {@link #GLOBAL_OWNER}; KNOWLEDGE buckets belong to one user. */
    @Column(name = "owner_user_id", nullable = false, length = 80)
    private String ownerUserId = GLOBAL_OWNER;

    @Column(name = "normalized_name", nullable = false, length = 120)
    private String normalizedName;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "domain_name", length = 120)
    private String domainName;

    @Column(name = "group_name", length = 120)
    private String groupName;

    @Column(length = 1200)
    private String description;

    @Column(name = "aliases_json", nullable = false, columnDefinition = "json")
    private String aliasesJson = "[]";

    @Column(nullable = false, length = 40)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CapabilityBucketStatus status = CapabilityBucketStatus.ACTIVE;

    @Column(name = "seed_version", length = 48)
    private String seedVersion;

    @Column(name = "classification_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal classificationConfidence = BigDecimal.ONE;

    @Column(name = "support_count", nullable = false)
    private int supportCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (ownerUserId == null || ownerUserId.isBlank()) ownerUserId = GLOBAL_OWNER;
        if (aliasesJson == null || aliasesJson.isBlank()) aliasesJson = "[]";
        if (status == null) status = CapabilityBucketStatus.ACTIVE;
        if (classificationConfidence == null) classificationConfidence = BigDecimal.ONE;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public CapabilityBucketType getBucketType() { return bucketType; }
    public void setBucketType(CapabilityBucketType bucketType) { this.bucketType = bucketType; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAliasesJson() { return aliasesJson; }
    public void setAliasesJson(String aliasesJson) { this.aliasesJson = aliasesJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public CapabilityBucketStatus getStatus() { return status; }
    public void setStatus(CapabilityBucketStatus status) { this.status = status; }
    public String getSeedVersion() { return seedVersion; }
    public void setSeedVersion(String seedVersion) { this.seedVersion = seedVersion; }
    public BigDecimal getClassificationConfidence() { return classificationConfidence; }
    public void setClassificationConfidence(BigDecimal classificationConfidence) { this.classificationConfidence = classificationConfidence; }
    public int getSupportCount() { return supportCount; }
    public void setSupportCount(int supportCount) { this.supportCount = supportCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
