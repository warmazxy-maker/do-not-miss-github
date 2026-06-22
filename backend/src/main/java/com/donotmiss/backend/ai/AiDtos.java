package com.donotmiss.backend.ai;

import com.donotmiss.backend.achievement.AchievementDtos;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.mcp.McpDtos;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class AiDtos {
    public record EventRecommendationRequest(
            @NotBlank String need,
            String category,
            String benefitType,
            String location,
            McpDtos.ToolContextRequest toolContext
    ) {
    }

    public record RecommendedEvent(
            EventDtos.EventResponse event,
            int score,
            String confidence,
            List<String> evidence,
            String reason
    ) {
    }

    public record EventRecommendationResponse(
            String mode,
            String need,
            String message,
            UserMemoryDtos.Profile memory,
            List<RecommendedEvent> recommendations,
            McpDtos.ToolContextResponse toolContext
    ) {
    }

    public record PlanRecommendationRequest(
            @NotBlank String goal,
            Integer horizonDays,
            String intensity,
            String location,
            McpDtos.ToolContextRequest toolContext
    ) {
    }

    public record PlanRecommendationResponse(
            String mode,
            String goal,
            String message,
            UserMemoryDtos.Profile memory,
            List<RecommendedPlan> plans,
            McpDtos.ToolContextResponse toolContext
    ) {
    }

    public record RecommendedPlan(
            String title,
            String style,
            String summary,
            List<PlanStep> steps,
            List<String> warnings,
            Integer qualityScore,
            List<String> agentTrace,
            List<PlanNode> nodes,
            List<PlanEdge> edges,
            List<ScheduleDraft> scheduleDrafts
    ) {
    }

    public record PlanStep(
            int order,
            String dateLabel,
            String title,
            String itemType,
            Long eventId,
            String scheduleHint,
            String reason
    ) {
    }

    public record PlanNode(
            String id,
            String type,
            String title,
            String subtitle,
            Long eventId,
            int order,
            String agent
    ) {
    }

    public record PlanEdge(
            String from,
            String to,
            String label
    ) {
    }

    public record ScheduleDraft(
            String title,
            String itemType,
            Long eventId,
            String dateLabel,
            String scheduleHint,
            String reason
    ) {
    }

    public record PlanGoalUnderstanding(
            String goal,
            String level,
            Integer horizonDays,
            String intensity,
            String preferredLocation,
            List<String> constraints,
            List<String> successCriteria,
            String searchQuery
    ) {
    }

    public record SelfAnalysisResponse(
            String mode,
            String summary,
            List<String> resumeBullets,
            List<String> strengths,
            List<String> suggestions,
            AchievementDtos.AchievementSummary chartData
    ) {
    }

    public record EventRecommendationModelResponse(
            List<EventRecommendationModelItem> recommendations
    ) {
    }

    public record EventRecommendationModelItem(
            Long eventId,
            Integer score,
            String reason,
            List<String> evidence
    ) {
    }

    public record EventRecommendationCriticResponse(
            List<EventRecommendationCriticItem> reviews
    ) {
    }

    public record EventRecommendationCriticItem(
            Long eventId,
            Boolean keep,
            Integer adjustedScore,
            String critique
    ) {
    }

    public record PlanRecommendationModelResponse(
            List<RecommendedPlan> plans
    ) {
    }

    public record PlanCriticModelResponse(
            List<PlanCriticItem> reviews
    ) {
    }

    public record PlanCriticItem(
            Integer planIndex,
            Boolean keep,
            Integer qualityScore,
            String critique,
            List<String> suggestions
    ) {
    }

    public record SelfAnalysisModelResponse(
            String summary,
            List<String> resumeBullets,
            List<String> strengths,
            List<String> suggestions
    ) {
    }
}
