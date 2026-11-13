package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.achievement.AchievementDtos;
import com.donotmiss.backend.achievement.AchievementService;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Completed activities and challenges with the student's reflections, in a date range of
 * completion. Unbounded over time, so the range is required. Pruned: the event body text,
 * location and internal source ids.
 */
@Component
public class GetAchievementHistoryTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_achievement_history", 1, "查成长记录",
            "查询学生在一个日期范围内完成的活动和挑战（按完成时间），包括学生写的心得：did 是做了什么，"
                    + "learned 是学到了什么；没写心得的就没有这两项。from、to 都包含在内。",
            ToolParams.DATE_RANGE, ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null,
            ToolDefinition.DEFAULT_TIMEOUT);

    private final AchievementService achievementService;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public GetAchievementHistoryTool(AchievementService achievementService, ObjectMapper objectMapper,
                                     @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.achievementService = achievementService;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return ToolOutput.DateRange.shortLabel(arguments);
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        ToolOutput.DateRange range = ToolOutput.DateRange.of(arguments);
        List<AchievementDtos.AchievementRecordResponse> records = achievementService.history(caller.userId()).stream()
                .filter(record -> record.completedAt() != null
                        && range.contains(LocalDateTime.ofInstant(record.completedAt(), zone)))
                .toList();
        long withReflection = records.stream().filter(record -> record.did() != null || record.learned() != null).count();
        return ToolOutput.list(objectMapper, records, this::fields,
                "完成了 " + records.size() + " 项，其中 " + withReflection + " 项写了心得");
    }

    private Map<String, Object> fields(AchievementDtos.AchievementRecordResponse record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", record.id());
        fields.put("source", "CHALLENGE".equals(record.sourceType()) ? "挑战" : "活动");
        fields.put("title", record.eventTitle());
        fields.put("organization", record.organizationName());
        fields.put("category", record.category());
        fields.put("eventStart", ToolOutput.time(record.eventStartTime()));
        fields.put("completedAt", ToolOutput.time(record.completedAt(), zone));
        fields.put("skill", record.skill());
        fields.put("benefit", record.benefitType());
        fields.put("did", ToolOutput.clip(record.did()));
        fields.put("learned", ToolOutput.clip(record.learned()));
        fields.values().removeIf(Objects::isNull);
        return fields;
    }
}
