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
import java.util.Objects;

/**
 * F2: change one schedule item. The card shows old → new. Both the change and its rollback are
 * guarded by a snapshot: the change only runs if the item still looks as it did when the card was
 * made, the rollback only if it still looks as the change left it. Either way a change the student
 * made by hand in between is never overwritten.
 */
@Component
public class UpdateScheduleItemTool implements ConfirmableTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "update_schedule_item", 1, "改日程",
            "修改学生日程表里的一个安排，item_id 来自“查日程”的结果。只填要改的字段；location、notes 填空字符串表示清空。"
                    + "不会直接执行：会生成一张卡片，学生点确认后才改。时间用当地时间 YYYY-MM-DDTHH:mm。",
            ToolParams.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "item_id": {"type": "integer", "minimum": 1, "description": "日程安排的 id"},
                        "title": {"type": "string", "minLength": 1, "maxLength": 160, "description": "新名称"},
                        "start": {"type": "string", "format": "date-time-local", "description": "新的开始时间"},
                        "end": {"type": "string", "format": "date-time-local", "description": "新的结束时间"},
                        "location": {"type": "string", "maxLength": 160, "description": "新地点；空字符串表示清空"},
                        "notes": {"type": "string", "maxLength": 1000, "description": "新备注；空字符串表示清空"},
                        %s
                      },
                      "required": ["item_id"],
                      "additionalProperties": false
                    }
                    """.formatted(WriteSupport.REPLACES_SCHEMA)),
            new ToolRisk(ToolRisk.DataEffect.CONFIRM_WRITE, ToolRisk.ModelUsage.NONE),
            Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final ScheduleService scheduleService;
    private final ScheduleItemRepository scheduleItems;
    private final ObjectMapper objectMapper;

    public UpdateScheduleItemTool(ScheduleService scheduleService, ScheduleItemRepository scheduleItems,
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
        return "#" + arguments.path("item_id").asText("");
    }

    @Override
    public Proposal propose(JsonNode arguments, ToolCaller caller) {
        ScheduleItemEntity item = active(arguments.path("item_id").asLong(), caller.userId());
        ObjectNode before = WriteSupport.snapshot(objectMapper, ScheduleDtos.ScheduleItemResponse.from(item));
        ObjectNode after = before.deepCopy();
        if (arguments.has("title")) {
            after.put("title", arguments.path("title").asText().trim());
        }
        if (arguments.has("start")) {
            after.put("start", LocalDateTime.parse(arguments.path("start").asText()).toString());
        }
        if (arguments.has("end")) {
            after.put("end", LocalDateTime.parse(arguments.path("end").asText()).toString());
        }
        if (arguments.has("location")) {
            after.put("location", WriteSupport.blankToNull(arguments.path("location").asText()));
        }
        if (arguments.has("notes")) {
            after.put("notes", WriteSupport.blankToNull(arguments.path("notes").asText()));
        }
        if (after.equals(before)) {
            throw ApiException.badRequest("没有要改的内容，和现在一样");
        }
        if (WriteSupport.text(after, "title") == null || WriteSupport.text(after, "title").isBlank()) {
            throw ApiException.badRequest("安排不能没有名称");
        }
        LocalDateTime start = WriteSupport.time(after, "start");
        LocalDateTime end = WriteSupport.time(after, "end");
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("结束时间要晚于开始时间");
        }

        List<ActionCard.Field> fields = new ArrayList<>();
        if (!Objects.equals(WriteSupport.text(before, "title"), WriteSupport.text(after, "title"))) {
            fields.add(change("名称", WriteSupport.text(before, "title"), WriteSupport.text(after, "title")));
        }
        String oldTime = WriteSupport.timeRange(WriteSupport.time(before, "start"), WriteSupport.time(before, "end"));
        String newTime = WriteSupport.timeRange(start, end);
        fields.add(oldTime.equals(newTime) ? new ActionCard.Field("时间", newTime) : change("时间", oldTime, newTime));
        if (!Objects.equals(WriteSupport.text(before, "location"), WriteSupport.text(after, "location"))) {
            fields.add(change("地点", WriteSupport.text(before, "location"), WriteSupport.text(after, "location")));
        }
        if (!Objects.equals(WriteSupport.text(before, "notes"), WriteSupport.text(after, "notes"))) {
            fields.add(change("备注", WriteSupport.clip(WriteSupport.text(before, "notes"), 100),
                    WriteSupport.clip(WriteSupport.text(after, "notes"), 100)));
        }
        List<String> warnings = new ArrayList<>(WriteSupport.clashes(scheduleService, caller.userId(), start, end,
                item.getId(), null, null));
        List<String> effects = new ArrayList<>();
        effects.add("只改这一条日程，别的不动");
        if ("RESERVATION".equals(item.getItemType().name())) {
            effects.add("这是预约活动带来的日程，改的只是你日程表里的记录，活动本身的时间不会变");
        }
        ActionCard card = new ActionCard("改日程", WriteSupport.text(before, "title"), fields, effects, warnings,
                "/student/schedule");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("itemId", item.getId());
        payload.set("before", before);
        payload.set("after", after);
        return new Proposal(card, payload);
    }

    @Override
    public Applied apply(JsonNode payload, String userId) {
        ScheduleItemEntity item = active(payload.path("itemId").asLong(), userId);
        if (!WriteSupport.snapshot(objectMapper, ScheduleDtos.ScheduleItemResponse.from(item)).equals(payload.path("before"))) {
            throw ApiException.badRequest("卡片生成之后这条日程被改过了，为了不覆盖你的修改，没有执行。可以让 Tandem 按现在的样子重新准备");
        }
        ScheduleDtos.ScheduleItemResponse updated = scheduleService.update(userId, item.getId(), request(payload.path("after")));
        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("itemId", updated.id());
        undo.set("after", WriteSupport.snapshot(objectMapper, updated));
        return new Applied("日程已修改", undo);
    }

    @Override
    public String undo(JsonNode payload, JsonNode undoData, String userId) {
        ScheduleItemEntity item = scheduleItems.findByIdAndUserId(undoData.path("itemId").asLong(), userId)
                .filter(found -> found.getStatus() == ScheduleItemStatus.ACTIVE)
                .orElseThrow(() -> ApiException.badRequest("这条日程已经不在日程表里了"));
        if (!WriteSupport.snapshot(objectMapper, ScheduleDtos.ScheduleItemResponse.from(item)).equals(undoData.path("after"))) {
            throw ApiException.badRequest("这条日程修改之后又被改过了，为了不覆盖你的修改，没有撤销。请到日程页处理");
        }
        scheduleService.update(userId, item.getId(), request(payload.path("before")));
        return "已撤销：这条日程改回了原来的样子";
    }

    private ScheduleItemEntity active(long itemId, String userId) {
        ScheduleItemEntity item = scheduleItems.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> ApiException.notFound("日程里没有这一条（#" + itemId + "）"));
        if (item.getStatus() != ScheduleItemStatus.ACTIVE) {
            throw ApiException.badRequest("这条日程已经删除了，不能修改");
        }
        return item;
    }

    private static ScheduleDtos.UpdateScheduleItemRequest request(JsonNode snapshot) {
        return new ScheduleDtos.UpdateScheduleItemRequest(WriteSupport.text(snapshot, "title"),
                WriteSupport.time(snapshot, "start"), WriteSupport.time(snapshot, "end"),
                WriteSupport.text(snapshot, "location"), WriteSupport.text(snapshot, "notes"));
    }

    private static ActionCard.Field change(String label, String before, String after) {
        return new ActionCard.Field(label, (before == null ? "（空）" : before) + " → " + (after == null ? "（空）" : after));
    }
}
