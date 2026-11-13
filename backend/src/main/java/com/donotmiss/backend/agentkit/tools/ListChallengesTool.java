package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.challenge.ChallengeDtos;
import com.donotmiss.backend.challenge.ChallengeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The student's challenges of one status. Active ones are few; finished ones fall under the safety cap. */
@Component
public class ListChallengesTool implements AgentTool {
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "ACTIVE", "进行中", "COMPLETED", "已完成", "CANCELLED", "已取消");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_challenges", 1, "查挑战",
            "查询学生自己建的挑战（没有合适活动时给自己定的小任务）。status 选 ACTIVE（进行中）、"
                    + "COMPLETED（已完成，带学生写的“做了什么 / 学到什么”）或 CANCELLED（已取消）。",
            ToolParams.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "status": {"type": "string", "enum": ["ACTIVE", "COMPLETED", "CANCELLED"],
                                   "description": "挑战状态"}
                      },
                      "required": ["status"],
                      "additionalProperties": false
                    }
                    """),
            ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final ChallengeService challengeService;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public ListChallengesTool(ChallengeService challengeService, ObjectMapper objectMapper,
                              @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.challengeService = challengeService;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return STATUS_LABELS.getOrDefault(arguments.path("status").asText(), "");
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        String status = arguments.path("status").asText();
        List<ChallengeDtos.ChallengeResponse> challenges = challengeService.list(caller.userId(), status);
        return ToolOutput.list(objectMapper, challenges, this::fields,
                STATUS_LABELS.get(status) + "的挑战有 " + challenges.size() + " 个");
    }

    private Map<String, Object> fields(ChallengeDtos.ChallengeResponse challenge) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", challenge.id());
        fields.put("title", challenge.title());
        fields.put("category", challenge.category());
        fields.put("goal", ToolOutput.clip(challenge.goal()));
        fields.put("description", ToolOutput.clip(challenge.description()));
        fields.put("status", STATUS_LABELS.getOrDefault(challenge.status(), challenge.status()));
        fields.put("createdAt", ToolOutput.time(challenge.createdAt(), zone));
        fields.put("completedAt", ToolOutput.time(challenge.completedAt(), zone));
        fields.put("did", ToolOutput.clip(challenge.did()));
        fields.put("learned", ToolOutput.clip(challenge.learned()));
        fields.values().removeIf(Objects::isNull);
        return fields;
    }
}
