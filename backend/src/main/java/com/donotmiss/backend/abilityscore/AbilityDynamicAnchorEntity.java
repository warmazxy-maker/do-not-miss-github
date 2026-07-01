package com.donotmiss.backend.abilityscore;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "ability_dynamic_anchors",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ability_dynamic_anchor_key", columnNames = "normalized_key")
        },
        indexes = {
                @Index(name = "idx_ability_dynamic_anchor_status_confidence", columnList = "status,confidence,updated_at")
        }
)
public class AbilityDynamicAnchorEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "normalized_key", nullable = false, length = 120)
    private String normalizedKey;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 600)
    private String description;

    @Column(name = "aliases_json", nullable = false, columnDefinition = "json")
    private String aliasesJson = "[]";

    @Column(name = "member_dimensions_json", nullable = false, columnDefinition = "json")
    private String memberDimensionsJson = "[]";

    @Column(nullable = false, length = 40)
    private String source = "AUTO_DISCOVERED";

    @Column(nullable = false, length = 32)
    private String status = "CANDIDATE";

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence = new BigDecimal("0.7000");

    @Column(name = "support_count", nullable = false)
    private int supportCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (aliasesJson == null || aliasesJson.isBlank()) {
            aliasesJson = "[]";
        }
        if (memberDimensionsJson == null || memberDimensionsJson.isBlank()) {
            memberDimensionsJson = "[]";
        }
        if (source == null || source.isBlank()) {
            source = "AUTO_DISCOVERED";
        }
        if (status == null || status.isBlank()) {
            status = "CANDIDATE";
        }
        if (confidence == null) {
            confidence = new BigDecimal("0.7000");
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getNormalizedKey() {
        return normalizedKey;
    }

    public void setNormalizedKey(String normalizedKey) {
        this.normalizedKey = normalizedKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAliasesJson() {
        return aliasesJson;
    }

    public void setAliasesJson(String aliasesJson) {
        this.aliasesJson = aliasesJson;
    }

    public String getMemberDimensionsJson() {
        return memberDimensionsJson;
    }

    public void setMemberDimensionsJson(String memberDimensionsJson) {
        this.memberDimensionsJson = memberDimensionsJson;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public int getSupportCount() {
        return supportCount;
    }

    public void setSupportCount(int supportCount) {
        this.supportCount = supportCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
