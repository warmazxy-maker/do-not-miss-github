package com.donotmiss.backend.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A role is data: adding one is inserting a row and its permissions, no code change. */
@Entity
@Table(name = "roles")
public class RoleEntity {
    public static final String STUDENT = "STUDENT";
    public static final String SOCIAL = "SOCIAL";
    public static final String ADMIN_ZERO = "ADMIN_ZERO";

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(name = "self_registrable", nullable = false)
    private boolean selfRegistrable;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSelfRegistrable() {
        return selfRegistrable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
