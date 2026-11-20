package com.donotmiss.backend.master.goal;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * F3: Tandem notes that it went over a goal with the student. Only its own bookkeeping (when, and
 * one line of what came out), so it runs without a card, like remember.
 */
@Component
public class RecordGoalReviewTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "record_goal_review", 1, "记录回顾",
            "和学生一起回顾完一个目标后调用，用一句话记下结论（进展如何、要不要调整、下一步）。"
                    + "只在真的和学生一起看过进展之后用；它不改目标本身，改目标用“改目标”。",
            CreateGoalTool.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "goal_id": {"type": "integer", "minimum": 1},
                        "note": {"type": "string", "minLength": 1, "maxLength": 300, "description": "这次回顾的结论"}
                      },
                      "required": ["goal_id", "note"],
                      "additionalProperties": false
                    }
                    """),
            new ToolRisk(ToolRisk.DataEffect.INTERNAL_WRITE, ToolRisk.ModelUsage.NONE),
            Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final TandemGoalService goals;

    public RecordGoalReviewTool(TandemGoalService goals) {
        this.goals = goals;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return "#" + arguments.path("goal_id").asText("");
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        goals.review(caller.userId(), arguments.path("goal_id").asLong(), arguments.path("note").asText().trim());
        return ToolResult.ok(null, "记下了这次回顾");
    }
}
