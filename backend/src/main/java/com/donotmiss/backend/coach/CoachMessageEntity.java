package com.donotmiss.backend.coach;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "coach_messages",
        indexes = @Index(name = "idx_coach_messages_user_date", columnList = "user_id,message_date,created_at")
)
public class CoachMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CoachMessageRole role;

    @Column(nullable = false, length = 3000)
    private String content;

    @Column(name = "message_date", nullable = false)
    private LocalDate messageDate;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public CoachMessageRole getRole() {
        return role;
    }

    public void setRole(CoachMessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDate getMessageDate() {
        return messageDate;
    }

    public void setMessageDate(LocalDate messageDate) {
        this.messageDate = messageDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
