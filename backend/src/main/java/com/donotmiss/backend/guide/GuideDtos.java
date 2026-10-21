package com.donotmiss.backend.guide;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.mcp.McpDtos;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public class GuideDtos {
    public record GuideChatRequest(
            @NotBlank String message,
            String sessionId,
            McpDtos.ToolContextRequest toolContext
    ) {
    }

    public record GuideChatResponse(
            String sessionId,
            String reply,
            GuideAction action,
            boolean usedRag,
            List<AiDtos.RecommendedEvent> recommendations,
            GuideSessionView session,
            McpDtos.ToolContextResponse toolContext
    ) {
    }

    public record GuideDecisionResponse(
            String sessionId,
            GuideDecision decision,
            GuideSessionView session
    ) {
    }

    public record GuideDecision(
            GuideAction action,
            boolean shouldCallRag,
            double confidence,
            String reason,
            String currentGoal,
            String ragNeed,
            String assistantHint,
            List<String> acceptedPreferences,
            List<String> rejectedConditions
    ) {
    }

    public record GuideActionModelResponse(
            String action,
            Double confidence,
            String reason,
            String currentGoal,
            String ragNeed,
            String assistantHint,
            List<String> acceptedPreferences,
            List<String> rejectedConditions
    ) {
    }

    public record GuideSessionView(
            String sessionId,
            String currentGoal,
            String sessionSummary,
            String lastAction,
            String lastRagNeed,
            List<GuideMessage> recentMessages,
            List<Long> lastRecommendedEventIds,
            List<String> acceptedPreferences,
            List<String> rejectedConditions,
            Instant updatedAt,
            Instant expiresAt
    ) {
    }

    public record GuideMessage(
            String role,
            String content,
            Instant at
    ) {
    }
}
