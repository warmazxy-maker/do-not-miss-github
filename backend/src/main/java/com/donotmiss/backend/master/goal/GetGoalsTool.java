package com.donotmiss.backend.master.goal;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** F3: the student's goals in full. Bounded (3 active, 5 past), so everything comes back. */
@Component
public class GetGoalsTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_goals", 1, "查目标",
            "查询学生的目标：进行中的（最多 3 个）和最近结束的，含每个步骤现在的状态、进度和已经带来证据的能力。"
                    + "系统每轮会给你一份简要的目标列表；需要细节（说明、回顾记录、时间）或回顾目标时再用这个。",
            CreateGoalTool.parse("{\"type\": \"object\", \"properties\": {}, \"additionalProperties\": false}"),
            ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final TandemGoalService goals;
    private final ObjectMapper objectMapper;

    public GetGoalsTool(TandemGoalService goals, ObjectMapper objectMapper) {
        this.goals = goals;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        TandemGoalService.Goals all = goals.list(caller.userId());
        return ToolResult.ok(objectMapper.valueToTree(all),
                "进行中 " + all.active().size() + " 个目标，过去的 " + all.past().size() + " 个");
    }
}
