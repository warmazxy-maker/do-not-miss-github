package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.schedule.ScheduleDtos;
import com.donotmiss.backend.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The student's schedule in a date range. Unbounded over time, so the range is required. */
@Component
public class GetScheduleTool implements AgentTool {
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "RESERVATION", "预约的活动",
            "CHALLENGE", "挑战",
            "AI_PLAN", "AI 计划",
            "CUSTOM", "自己添加");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_schedule", 1, "查日程",
            "查询学生日程表在一个日期范围内的安排，包括预约后自动加入的活动、挑战和学生自己添加的安排。"
                    + "from、to 都包含在内。学生说“这周”“下个月”时，先换算成具体日期。",
            ToolParams.DATE_RANGE, ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null,
            ToolDefinition.DEFAULT_TIMEOUT);

    private final ScheduleService scheduleService;
    private final ObjectMapper objectMapper;

    public GetScheduleTool(ScheduleService scheduleService, ObjectMapper objectMapper) {
        this.scheduleService = scheduleService;
        this.objectMapper = objectMapper;
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
        List<ScheduleDtos.ScheduleItemResponse> items = scheduleService.list(caller.userId(), null).stream()
                .filter(item -> range.contains(item.startTime()))
                .toList();
        return ToolOutput.list(objectMapper, items, this::fields,
                // The range is already shown next to the title (describe), so not repeated here.
                "有 " + items.size() + " 个安排");
    }

    private Map<String, Object> fields(ScheduleDtos.ScheduleItemResponse item) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", item.id());
        fields.put("type", TYPE_LABELS.getOrDefault(item.itemType(), item.itemType()));
        fields.put("title", item.title());
        fields.put("start", ToolOutput.time(item.startTime()));
        fields.put("end", ToolOutput.time(item.endTime()));
        fields.put("location", item.location());
        fields.put("notes", ToolOutput.clip(item.notes()));
        fields.values().removeIf(Objects::isNull);
        return fields;
    }
}
