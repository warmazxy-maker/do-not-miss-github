package com.donotmiss.backend.resumeinterview;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "resume_interview_questions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_resume_question_sequence",
                columnNames = {"session_id", "sequence_no"}
        ),
        indexes = @Index(name = "idx_resume_question_session_status", columnList = "session_id,status")
)
public class ResumeInterviewQuestionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResumeInterviewQuestionStatus status = ResumeInterviewQuestionStatus.DRAFT;

    @Column(name = "target_dimension", nullable = false, length = 120)
    private String targetDimension;

    @Column(name = "normalized_dimension", nullable = false, length = 120)
    private String normalizedDimension;

    @Column(name = "question_text", nullable = false, length = 1200)
    private String questionText;

    @Column(name = "evidence_refs_json", nullable = false, columnDefinition = "json")
    private String evidenceRefsJson = "[]";

    @Column(name = "generation_reason", length = 1000)
    private String generationReason;

    @Column(name = "agent_trace_run_id")
    private Long agentTraceRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @PrePersist
    void prePersist() {
        if (status == null) {
            status = ResumeInterviewQuestionStatus.DRAFT;
        }
        if (evidenceRefsJson == null) {
            evidenceRefsJson = "[]";
        }
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

    public int getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(int sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public ResumeInterviewQuestionStatus getStatus() {
        return status;
    }

    public void setStatus(ResumeInterviewQuestionStatus status) {
        this.status = status;
    }

    public String getTargetDimension() {
        return targetDimension;
    }

    public void setTargetDimension(String targetDimension) {
        this.targetDimension = targetDimension;
    }

    public String getNormalizedDimension() {
        return normalizedDimension;
    }

    public void setNormalizedDimension(String normalizedDimension) {
        this.normalizedDimension = normalizedDimension;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getEvidenceRefsJson() {
        return evidenceRefsJson;
    }

    public void setEvidenceRefsJson(String evidenceRefsJson) {
        this.evidenceRefsJson = evidenceRefsJson;
    }

    public String getGenerationReason() {
        return generationReason;
    }

    public void setGenerationReason(String generationReason) {
        this.generationReason = generationReason;
    }

    public Long getAgentTraceRunId() {
        return agentTraceRunId;
    }

    public void setAgentTraceRunId(Long agentTraceRunId) {
        this.agentTraceRunId = agentTraceRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(Instant answeredAt) {
        this.answeredAt = answeredAt;
    }
}
