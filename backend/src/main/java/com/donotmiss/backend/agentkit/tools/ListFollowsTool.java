package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.follow.FollowDtos;
import com.donotmiss.backend.follow.FollowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The organisations the student follows. Bounded, so all of them. */
@Component
public class ListFollowsTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_followed_organizations", 1, "查关注的组织",
            "列出学生关注的组织（社团、企业等），以及开始关注的时间。不需要参数。",
            ToolParams.NONE, ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final FollowService followService;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public ListFollowsTool(FollowService followService, ObjectMapper objectMapper,
                           @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.followService = followService;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        List<FollowDtos.FollowResponse> follows = followService.list(caller.userId());
        return ToolOutput.list(objectMapper, follows, follow -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("organization", follow.organizationName());
            fields.put("since", ToolOutput.time(follow.createdAt(), zone));
            return fields;
        }, "关注了 " + follows.size() + " 个组织");
    }
}
