package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.ai.AiService;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Searches events through the existing recommendation pipeline (the same one as the AI search on
 * the events page), so it calls the model several times and is charged as heavy AI. The shown
 * results are recorded as recommendation impressions, like on the events page.
 */
@Component
public class SearchEventsTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "search_events", 1, "搜活动",
            "按学生的需求搜索平台上可以参加的活动，返回推荐结果和推荐理由。need 用学生的话把需求写完整，"
                    + "包括想练什么、时间、线上线下等条件。这个工具比较慢、比较贵，同一个需求不要重复搜。",
            ToolParams.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "need": {"type": "string", "minLength": 2, "maxLength": 300, "description": "学生的需求"}
                      },
                      "required": ["need"],
                      "additionalProperties": false
                    }
                    """),
            new ToolRisk(ToolRisk.DataEffect.READ, ToolRisk.ModelUsage.CALLS_MODEL),
            Permission.STUDENT_FEATURES, RateLimitTier.AI_HEAVY,
            // The pipeline makes several model calls in a row (5～47 s seen); leaves room in the 90 s turn.
            Duration.ofSeconds(60));

    private final AiService aiService;
    private final ObjectMapper objectMapper;

    public SearchEventsTool(AiService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return "“" + arguments.path("need").asText() + "”";
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        AiDtos.EventRecommendationResponse response = aiService.recommendEvents(caller.userId(),
                new AiDtos.EventRecommendationRequest(arguments.path("need").asText(), null, null, null, null));
        List<AiDtos.RecommendedEvent> items = response.recommendations() == null ? List.of() : response.recommendations();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", response.mode());
        data.put("message", response.message());
        data.put("recommendationRunId", response.runId());
        data.put("total", items.size());
        data.put("items", items.stream().limit(ToolOutput.MAX_ITEMS).map(this::fields).toList());
        return ToolOutput.object(objectMapper, data, "找到 " + items.size() + " 个推荐活动");
    }

    private Map<String, Object> fields(AiDtos.RecommendedEvent item) {
        Map<String, Object> fields = new LinkedHashMap<>();
        var event = item.event();
        fields.put("eventId", event.id());
        fields.put("title", event.title());
        fields.put("organization", event.organizationName());
        fields.put("category", event.category());
        fields.put("start", ToolOutput.time(event.startTime()));
        fields.put("end", ToolOutput.time(event.endTime()));
        fields.put("location", event.location());
        fields.put("status", event.studentStatusLabel());
        fields.put("benefit", event.benefitType());
        fields.put("skill", event.skill());
        fields.put("score", item.score());
        fields.put("confidence", item.confidence());
        fields.put("reason", ToolOutput.clip(item.reason()));
        fields.put("evidence", item.evidence());
        fields.values().removeIf(Objects::isNull);
        return fields;
    }
}
