package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.agentkit.actions.ActionCard;
import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.schedule.ScheduleDtos;
import com.donotmiss.backend.schedule.ScheduleItemEntity;
import com.donotmiss.backend.schedule.ScheduleItemRepository;
import com.donotmiss.backend.schedule.ScheduleItemStatus;
import com.donotmiss.backend.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** F2: add a student's own item to the schedule. Rollback removes it again, if untouched. */
@Component
public class AddScheduleItemTool implements ConfirmableTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "add_schedule_item", 1, "加日程",
            "在学生的日程表里加一个自己的安排（不是预约活动；预约活动用“预约活动”，日程会自动加上）。"
                    + "不会直接执行：会生成一张卡片，学生点确认后才加。时间用当地时间 YYYY-MM-DDTHH:mm，"
                    + "学生说“明天下午三点”时先换算成具体时间；没说结束时间就问他，或按常识给一个并在回答里说明。",
            ToolParams.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "title": {"type": "string", "minLength": 1, "maxLength": 160, "description": "安排的名称"},
                        "start": {"type": "string", "format": "date-time-local", "description": "开始时间 YYYY-MM-DDTHH:mm"},
                        "end": {"type": "string", "format": "date-time-local", "description": "结束时间 YYYY-MM-DDTHH:mm"},
                        "location": {"type": "string", "maxLength": 160, "description": "地点，可不填"},
                        "notes": {"type": "string", "maxLength": 1000, "description": "备注，可不填"},
                        %s
                      },
                      "required": ["title", "start", "end"],
                      "additionalProperties": false
                    }
                    """.formatted(WriteSupport.REPLACES_SCHEMA)),
            new ToolRisk(ToolRisk.DataEffect.CONFIRM_WRITE, ToolRisk.ModelUsage.NONE),
            Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final ScheduleService scheduleService;
    private final ScheduleItemRepository scheduleItems;
    private final ObjectMapper objectMapper;

    public AddScheduleItemTool(ScheduleService scheduleService, ScheduleItemRepository scheduleItems,
                               ObjectMapper objectMapper) {
        this.scheduleService = scheduleService;
        this.scheduleItems = scheduleItems;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return arguments.path("title").asText("");
    }

    @Override
    public Proposal propose(JsonNode arguments, ToolCaller caller) {
        String title = arguments.path("title").asText().trim();
        LocalDateTime start = WriteSupport.time(arguments, "start");
        LocalDateTime end = WriteSupport.time(arguments, "end");
        String location = WriteSupport.blankToNull(WriteSupport.text(arguments, "location"));
        String notes = WriteSupport.blankToNull(WriteSupport.text(arguments, "notes"));
        if (title.isEmpty()) {
            throw ApiException.badRequest("安排没有名称");
        }
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("结束时间要晚于开始时间");
        }

        List<ActionCard.Field> fields = new ArrayList<>();
        fields.add(new ActionCard.Field("时间", WriteSupport.timeRange(start, end)));
        if (location != null) {
            fields.add(new ActionCard.Field("地点", location));
        }
        if (notes != null) {
            fields.add(new ActionCard.Field("备注", WriteSupport.clip(notes, 200)));
        }
        List<String> warnings = new ArrayList<>();
        if (start.isBefore(LocalDateTime.now())) {
            warnings.add("开始时间已经过去了");
        }
        warnings.addAll(WriteSupport.clashes(scheduleService, caller.userId(), start, end, null, null, null));
        ActionCard card = new ActionCard("加日程", title, fields,
                List.of("加到你的日程表里，类型是“自己添加”"), warnings, "/student/schedule");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", title);
        payload.put("start", start.toString());
        payload.put("end", end.toString());
        payload.put("location", location);
        payload.put("notes", notes);
        return new Proposal(card, payload);
    }

    @Override
    public Applied apply(JsonNode payload, String userId) {
        ScheduleDtos.ScheduleItemResponse created = scheduleService.create(userId, new ScheduleDtos.CreateScheduleItemRequest(
                WriteSupport.text(payload, "title"), "CUSTOM", null,
                WriteSupport.time(payload, "start"), WriteSupport.time(payload, "end"),
                WriteSupport.text(payload, "location"), WriteSupport.text(payload, "notes")));
        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("itemId", created.id());
        undo.set("after", WriteSupport.snapshot(objectMapper, created));
        return new Applied("已加到日程表", undo);
    }

    @Override
    public String undo(JsonNode payload, JsonNode undoData, String userId) {
        ScheduleItemEntity item = scheduleItems.findByIdAndUserId(undoData.path("itemId").asLong(), userId)
                .filter(found -> found.getStatus() == ScheduleItemStatus.ACTIVE)
                .orElseThrow(() -> ApiException.badRequest("这条日程已经不在日程表里了"));
        if (!WriteSupport.snapshot(objectMapper, ScheduleDtos.ScheduleItemResponse.from(item)).equals(undoData.path("after"))) {
            throw ApiException.badRequest("这条日程加上之后被改过了，为了不覆盖你的修改，没有撤销。请到日程页处理");
        }
        scheduleService.cancel(userId, item.getId());
        return "已撤销：这条日程已从日程表删除";
    }
}
