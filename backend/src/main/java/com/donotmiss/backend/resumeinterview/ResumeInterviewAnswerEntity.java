package com.donotmiss.backend.resumeinterview;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "resume_interview_answers",
        uniqueConstraints = @UniqueConstraint(name = "uk_resume_answer_question", columnNames = "question_id"),
        indexes = @Index(name = "idx_resume_answer_session_created", columnList = "session_id,created_at")
)
public class ResumeInterviewAnswerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "answer_text", nullable = false, columnDefinition = "longtext")
    private String answerText;

    @Column(name = "answer_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String answerHash;

    @Column(name = "created_at", nullable = false)
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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public String getAnswerHash() {
        return answerHash;
    }

    public void setAnswerHash(String answerHash) {
        this.answerHash = answerHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
