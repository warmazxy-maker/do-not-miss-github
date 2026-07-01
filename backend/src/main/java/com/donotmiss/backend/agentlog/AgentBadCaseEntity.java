package com.donotmiss.backend.agentlog;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "agent_bad_cases",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_agent_bad_cases_case_key", columnNames = "case_key")
        },
        indexes = {
                @Index(name = "idx_agent_bad_cases_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_agent_bad_cases_status_created", columnList = "status,created_at"),
                @Index(name = "idx_agent_bad_cases_run", columnList = "agent_run_id"),
                @Index(name = "idx_agent_bad_cases_issue_status", columnList = "issue_type,status"),
                @Index(name = "idx_agent_bad_cases_root_step", columnList = "root_cause_step")
        }
)
public class AgentBadCaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_key", nullable = false, length = 40)
    private String caseKey;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Column(name = "agent_run_id")
    private Long agentRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", length = 48)
    private AgentRunType runType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private AgentBadCaseSourceType sourceType = AgentBadCaseSourceType.USER_FEEDBACK;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 64)
    private AgentBadCaseIssueType issueType = AgentBadCaseIssueType.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentBadCaseSeverity severity = AgentBadCaseSeverity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentBadCaseStatus status = AgentBadCaseStatus.OPEN;

    @Column(name = "page_url", length = 500)
    private String pageUrl;

    @Column(name = "module_key", length = 80)
    private String moduleKey;

    @Column(name = "user_message", nullable = false, length = 2000)
    private String userMessage;

    @Column(name = "expected_behavior", length = 2000)
    private String expectedBehavior;

    @Column(name = "actual_behavior", length = 2000)
    private String actualBehavior;

    @Enumerated(EnumType.STRING)
    @Column(name = "root_cause_step", length = 64)
    private AgentStepName rootCauseStep;

    @Column(name = "root_cause_summary", length = 2000)
    private String rootCauseSummary;

    @Column(name = "relevant_artifact_ids", columnDefinition = "json")
    private String relevantArtifactIds;

    @Column(name = "analysis_json", columnDefinition = "json")
    private String analysisJson;

    @Column(name = "agent_memory_candidate", nullable = false)
    private boolean agentMemoryCandidate = false;

    @Column(name = "eval_case_candidate", nullable = false)
    private boolean evalCaseCandidate = false;

    @Column(name = "resolution_summary", length = 2000)
    private String resolutionSummary;

    @Column(name = "reviewer_id", length = 80)
    private String reviewerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "triaged_at")
    private Instant triagedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (caseKey == null || caseKey.isBlank()) {
            caseKey = UUID.randomUUID().toString();
        }
        if (sourceType == null) {
            sourceType = AgentBadCaseSourceType.USER_FEEDBACK;
        }
        if (issueType == null) {
            issueType = AgentBadCaseIssueType.UNKNOWN;
        }
        if (severity == null) {
            severity = AgentBadCaseSeverity.MEDIUM;
        }
        if (status == null) {
            status = AgentBadCaseStatus.OPEN;
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

    public String getCaseKey() {
        return caseKey;
    }

    public void setCaseKey(String caseKey) {
        this.caseKey = caseKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getAgentRunId() {
        return agentRunId;
    }

    public void setAgentRunId(Long agentRunId) {
        this.agentRunId = agentRunId;
    }

    public AgentRunType getRunType() {
        return runType;
    }

    public void setRunType(AgentRunType runType) {
        this.runType = runType;
    }

    public AgentBadCaseSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(AgentBadCaseSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public AgentBadCaseIssueType getIssueType() {
        return issueType;
    }

    public void setIssueType(AgentBadCaseIssueType issueType) {
        this.issueType = issueType;
    }

    public AgentBadCaseSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AgentBadCaseSeverity severity) {
        this.severity = severity;
    }

    public AgentBadCaseStatus getStatus() {
        return status;
    }

    public void setStatus(AgentBadCaseStatus status) {
        this.status = status;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getModuleKey() {
        return moduleKey;
    }

    public void setModuleKey(String moduleKey) {
        this.moduleKey = moduleKey;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getExpectedBehavior() {
        return expectedBehavior;
    }

    public void setExpectedBehavior(String expectedBehavior) {
        this.expectedBehavior = expectedBehavior;
    }

    public String getActualBehavior() {
        return actualBehavior;
    }

    public void setActualBehavior(String actualBehavior) {
        this.actualBehavior = actualBehavior;
    }

    public AgentStepName getRootCauseStep() {
        return rootCauseStep;
    }

    public void setRootCauseStep(AgentStepName rootCauseStep) {
        this.rootCauseStep = rootCauseStep;
    }

    public String getRootCauseSummary() {
        return rootCauseSummary;
    }

    public void setRootCauseSummary(String rootCauseSummary) {
        this.rootCauseSummary = rootCauseSummary;
    }

    public String getRelevantArtifactIds() {
        return relevantArtifactIds;
    }

    public void setRelevantArtifactIds(String relevantArtifactIds) {
        this.relevantArtifactIds = relevantArtifactIds;
    }

    public String getAnalysisJson() {
        return analysisJson;
    }

    public void setAnalysisJson(String analysisJson) {
        this.analysisJson = analysisJson;
    }

    public boolean isAgentMemoryCandidate() {
        return agentMemoryCandidate;
    }

    public void setAgentMemoryCandidate(boolean agentMemoryCandidate) {
        this.agentMemoryCandidate = agentMemoryCandidate;
    }

    public boolean isEvalCaseCandidate() {
        return evalCaseCandidate;
    }

    public void setEvalCaseCandidate(boolean evalCaseCandidate) {
        this.evalCaseCandidate = evalCaseCandidate;
    }

    public String getResolutionSummary() {
        return resolutionSummary;
    }

    public void setResolutionSummary(String resolutionSummary) {
        this.resolutionSummary = resolutionSummary;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getTriagedAt() {
        return triagedAt;
    }

    public void setTriagedAt(Instant triagedAt) {
        this.triagedAt = triagedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
