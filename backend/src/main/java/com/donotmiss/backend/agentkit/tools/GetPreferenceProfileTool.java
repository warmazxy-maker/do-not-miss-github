package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.memory.UserMemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The preference profile the platform infers from behaviour (B3 snapshot). Reads the stored
 * snapshot, or the rule-based profile when there is none yet; never calls the model.
 */
@Component
public class GetPreferenceProfileTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_preference_profile", 1, "查偏好画像",
            "读取平台根据学生的行为推断出来的偏好画像：概括、强项、偏好的活动类别和地点、看重的收获，"
                    + "以及在过往推荐里接受和拒绝过什么。这是系统推断的，不是学生亲口说的；和学生说的冲突时，以学生说的为准。不需要参数。",
            ToolParams.NONE, ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final UserMemoryService userMemoryService;
    private final ObjectMapper objectMapper;

    public GetPreferenceProfileTool(UserMemoryService userMemoryService, ObjectMapper objectMapper) {
        this.userMemoryService = userMemoryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        UserMemoryDtos.Profile profile = userMemoryService.profile(caller.userId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", ToolOutput.clip(profile.summary()));
        data.put("strengths", profile.strengths());
        data.put("preferredCategories", profile.preferredCategories());
        data.put("preferredLocations", profile.preferredLocations());
        data.put("benefitPreferences", profile.benefitPreferences());
        data.put("likedInRecommendations", profile.recommendationLikes());
        data.put("avoidedInRecommendations", profile.recommendationAvoids());
        data.put("completedCount", profile.completedCount());
        data.put("activeChallengeCount", profile.activeChallengeCount());
        return ToolOutput.object(objectMapper, data, "读了偏好画像");
    }
}
