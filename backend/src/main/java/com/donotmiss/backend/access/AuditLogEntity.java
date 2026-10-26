package com.donotmiss.backend.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "audit_logs",
        indexes = {
                @Index(name = "idx_audit_created", columnList = "created_at"),
                @Index(name = "idx_audit_user", columnList = "user_id,created_at"),
                @Index(name = "idx_audit_outcome", columnList = "outcome,created_at")
        }
)
public class AuditLogEntity {
    public enum Outcome { ALLOWED, FAILED, DENIED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 80)
    private String userId;

    @Column(name = "role_code", length = 32)
    private String roleCode;

    @Column(length = 48)
    private String permission;

    @Column(nullable = false, length = 120)
    private String action;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(length = 300)
    private String path;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static AuditLogEntity of(String userId, String roleCode, String permission, String action,
                                    String httpMethod, String path, Outcome outcome, String detail) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.userId = userId;
        entry.roleCode = roleCode;
        entry.permission = permission;
        entry.action = truncate(action, 120);
        entry.httpMethod = httpMethod;
        entry.path = truncate(path, 300);
        entry.outcome = outcome.name();
        entry.detail = truncate(detail, 500);
        return entry;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public String getPermission() {
        return permission;
    }

    public String getAction() {
        return action;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
