package com.donotmiss.backend.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A key fact the student said themselves (F1 decision 2). Data a tool can look up (events,
 * reservations, scores, resume) never goes here: a copy would go stale.
 */
@Entity
@Table(name = "tandem_memories")
public class TandemMemoryEntity {
    public enum Category {
        PERSONAL("个人情况"),
        CONSTRAINT("约束"),
        PREFERENCE("偏好和反感"),
        INTENTION("意向"),
        COMMITMENT("约定");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Category category;

    @Column(nullable = false, length = 300)
    private String content;

    @Column(name = "source_quote", length = 300)
    private String sourceQuote;

    @Column(name = "source_run_id")
    private Long sourceRunId;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TandemMemoryEntity() {
    }

    public static TandemMemoryEntity create(String userId, Category category, String content, String sourceQuote,
                                            Long sourceRunId, LocalDate validUntil) {
        TandemMemoryEntity memory = new TandemMemoryEntity();
        memory.userId = userId;
        memory.createdAt = Instant.now();
        memory.update(category, content, sourceQuote, sourceRunId, validUntil);
        return memory;
    }

    /** The same fact changed (e.g. N2 became N1): the row is updated, not duplicated. */
    public void update(Category category, String content, String sourceQuote, Long sourceRunId, LocalDate validUntil) {
        this.category = category;
        this.content = content;
        this.sourceQuote = sourceQuote;
        this.sourceRunId = sourceRunId;
        this.validUntil = validUntil;
        this.updatedAt = Instant.now();
    }

    public boolean validOn(LocalDate day) {
        return validUntil == null || !validUntil.isBefore(day);
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public String getContent() {
        return content;
    }

    public String getSourceQuote() {
        return sourceQuote;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
