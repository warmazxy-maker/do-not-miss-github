package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "resume_interview_evidence_candidates",
        indexes = {
                @Index(name = "idx_resume_evidence_session_status", columnList = "session_id,status"),
                @Index(name = "idx_resume_evidence_hash", columnList = "evidence_hash")
        }
)
public class ResumeInterviewEvidenceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "resume_snapshot_id")
    private Long resumeSnapshotId;

    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "answer_id")
    private Long answerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_kind", nullable = false, length = 32)
    private ResumeInterviewEvidenceKind evidenceKind = ResumeInterviewEvidenceKind.RESUME_AND_INTERVIEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 48)
    private AbilityEvidenceSourceType sourceType = AbilityEvidenceSourceType.RESUME_INTERVIEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResumeInterviewEvidenceStatus status = ResumeInterviewEvidenceStatus.DRAFT;

    @Column(name = "evidence_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String evidenceHash;

    @Column(name = "ability_evidence_json", nullable = false, columnDefinition = "json")
    private String abilityEvidenceJson = "{}";

    @Column(name = "ability_assessment_id")
    private Long abilityAssessmentId;

    @Column(name = "ability_score_result_ids_json", nullable = false, columnDefinition = "json")
    private String abilityScoreResultIdsJson = "[]";

    @Column(name = "error_message", length = 1200)
    private String errorMessage;

    @Column(name = "agent_trace_run_id")
    private Long agentTraceRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (evidenceKind == null) {
            evidenceKind = ResumeInterviewEvidenceKind.RESUME_AND_INTERVIEW;
        }
        if (sourceType == null) {
            sourceType = AbilityEvidenceSourceType.RESUME_INTERVIEW;
        }
        if (status == null) {
            status = ResumeInterviewEvidenceStatus.DRAFT;
        }
        if (abilityEvidenceJson == null) {
            abilityEvidenceJson = "{}";
        }
        if (abilityScoreResultIdsJson == null) {
            abilityScoreResultIdsJson = "[]";
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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getResumeSnapshotId() {
        return resumeSnapshotId;
    }

    public void setResumeSnapshotId(Long resumeSnapshotId) {
        this.resumeSnapshotId = resumeSnapshotId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getAnswerId() {
        return answerId;
    }

    public void setAnswerId(Long answerId) {
        this.answerId = answerId;
    }

    public ResumeInterviewEvidenceKind getEvidenceKind() {
        return evidenceKind;
    }

    public void setEvidenceKind(ResumeInterviewEvidenceKind evidenceKind) {
        this.evidenceKind = evidenceKind;
    }

    public AbilityEvidenceSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(AbilityEvidenceSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public ResumeInterviewEvidenceStatus getStatus() {
        return status;
    }

    public void setStatus(ResumeInterviewEvidenceStatus status) {
        this.status = status;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public void setEvidenceHash(String evidenceHash) {
        this.evidenceHash = evidenceHash;
    }

    public String getAbilityEvidenceJson() {
        return abilityEvidenceJson;
    }

    public void setAbilityEvidenceJson(String abilityEvidenceJson) {
        this.abilityEvidenceJson = abilityEvidenceJson;
    }

    public Long getAbilityAssessmentId() {
        return abilityAssessmentId;
    }

    public void setAbilityAssessmentId(Long abilityAssessmentId) {
        this.abilityAssessmentId = abilityAssessmentId;
    }

    public String getAbilityScoreResultIdsJson() {
        return abilityScoreResultIdsJson;
    }

    public void setAbilityScoreResultIdsJson(String abilityScoreResultIdsJson) {
        this.abilityScoreResultIdsJson = abilityScoreResultIdsJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
