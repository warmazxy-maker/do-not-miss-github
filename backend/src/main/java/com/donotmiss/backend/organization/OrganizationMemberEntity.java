package com.donotmiss.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** One organisation account belonging to one organisation; an organisation can have several. */
@Entity
@Table(
        name = "organization_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_org_member", columnNames = {"organization_id", "user_id"}),
        indexes = @Index(name = "idx_org_member_user", columnList = "user_id")
)
public class OrganizationMemberEntity {
    /** INFERRED: from past events (migration); CLAIMED: first to publish; AUTO_JOINED: joined an organisation that already had members. */
    public enum Source { INFERRED, CLAIMED, AUTO_JOINED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static OrganizationMemberEntity of(Long organizationId, String userId, Source source) {
        OrganizationMemberEntity member = new OrganizationMemberEntity();
        member.organizationId = organizationId;
        member.userId = userId;
        member.source = source;
        return member;
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getUserId() {
        return userId;
    }

    public Source getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
