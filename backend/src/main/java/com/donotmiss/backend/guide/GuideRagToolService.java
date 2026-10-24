package com.donotmiss.backend.guide;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.ai.AiService;
import com.donotmiss.backend.mcp.McpDtos;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GuideRagToolService {
    private final AiService aiService;

    public GuideRagToolService(AiService aiService) {
        this.aiService = aiService;
    }

    public ToolResult recommendEvents(String userId,
                                      GuideDtos.GuideDecision decision,
                                      McpDtos.ToolContextRequest toolContextRequest) {
        String need = ragNeed(decision);
        AiDtos.EventRecommendationResponse response = aiService.recommendEvents(
                userId,
                new AiDtos.EventRecommendationRequest(
                        need,
                        null,
                        null,
                        null,
                        toolContextRequest
                )
        );
        return new ToolResult(
                response.message(),
                response.recommendations(),
                response.toolContext(),
                response.runId()
        );
    }

    private String ragNeed(GuideDtos.GuideDecision decision) {
        if (decision == null) {
            return "";
        }
        if (hasText(decision.ragNeed())) {
            return decision.ragNeed().trim();
        }
        return decision.currentGoal() == null ? "" : decision.currentGoal().trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ToolResult(
            String message,
            List<AiDtos.RecommendedEvent> recommendations,
            McpDtos.ToolContextResponse toolContext,
            Long runId
    ) {
    }
}
