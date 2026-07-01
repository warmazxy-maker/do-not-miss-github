package com.donotmiss.backend.agentlog;

import java.time.Instant;

public class AgentBadCaseDtos {
    public record CreateRequest(
            Long agentRunId,
            AgentBadCaseSourceType sourceType,
            AgentBadCaseIssueType issueType,
            AgentBadCaseSeverity severity,
            String pageUrl,
            String moduleKey,
            String userMessage,
            String expectedBehavior,
            String actualBehavior
    ) {
    }

    public record Response(
            Long id,
            String caseKey,
            String userId,
            Long agentRunId,
            AgentRunType runType,
            AgentBadCaseSourceType sourceType,
            AgentBadCaseIssueType issueType,
            AgentBadCaseSeverity severity,
            AgentBadCaseStatus status,
            String pageUrl,
            String moduleKey,
            String userMessage,
            String expectedBehavior,
            String actualBehavior,
            AgentStepName rootCauseStep,
            String rootCauseSummary,
            String relevantArtifactIds,
            String analysisJson,
            boolean agentMemoryCandidate,
            boolean evalCaseCandidate,
            String resolutionSummary,
            String reviewerId,
            Instant createdAt,
            Instant updatedAt,
            Instant triagedAt,
            Instant resolvedAt
    ) {
        static Response from(AgentBadCaseEntity entity) {
            return new Response(
                    entity.getId(),
                    entity.getCaseKey(),
                    entity.getUserId(),
                    entity.getAgentRunId(),
                    entity.getRunType(),
                    entity.getSourceType(),
                    entity.getIssueType(),
                    entity.getSeverity(),
                    entity.getStatus(),
                    entity.getPageUrl(),
                    entity.getModuleKey(),
                    entity.getUserMessage(),
                    entity.getExpectedBehavior(),
                    entity.getActualBehavior(),
                    entity.getRootCauseStep(),
                    entity.getRootCauseSummary(),
                    entity.getRelevantArtifactIds(),
                    entity.getAnalysisJson(),
                    entity.isAgentMemoryCandidate(),
                    entity.isEvalCaseCandidate(),
                    entity.getResolutionSummary(),
                    entity.getReviewerId(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt(),
                    entity.getTriagedAt(),
                    entity.getResolvedAt()
            );
        }
    }
}
