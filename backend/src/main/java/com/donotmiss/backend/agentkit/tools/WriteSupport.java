package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.schedule.ScheduleDtos;
import com.donotmiss.backend.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Shared pieces of the F2 write tools: time labels, clash warnings, snapshots for rollback. */
final class WriteSupport {
    static final String REPLACES_SCHEMA = """
            "%s": {"type": "integer", "minimum": 1,
                   "description": "要替换的旧卡片编号。学生想改一张还没确认的卡片时填它，旧卡片会作废"}
            """.formatted(ConfirmableTool.REPLACES_ARGUMENT);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日（E）", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_CLASHES = 3;

    private WriteSupport() {
    }

    /** "11月21日（周五）14:00–15:30"; an end on another day gets its own date. */
    static String timeRange(LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            return "";
        }
        String text = start.format(DAY) + " " + start.format(TIME);
        if (end == null) {
            return text;
        }
        return text + "–" + (end.toLocalDate().equals(start.toLocalDate()) ? "" : end.format(DAY) + " ") + end.format(TIME);
    }

    /** Active schedule items that overlap the range; a warning on the card, never a block. */
    static List<String> clashes(ScheduleService schedule, String userId, LocalDateTime start, LocalDateTime end,
                                Long ignoreItemId, String ignoreType, Long ignoreSourceId) {
        List<ScheduleDtos.ScheduleItemResponse> overlapping = schedule.list(userId, null).stream()
                .filter(item -> !Objects.equals(item.id(), ignoreItemId))
                .filter(item -> ignoreType == null
                        || !(ignoreType.equals(item.itemType()) && Objects.equals(item.sourceId(), ignoreSourceId)))
                .filter(item -> item.startTime() != null && item.endTime() != null
                        && item.startTime().isBefore(end) && start.isBefore(item.endTime()))
                .toList();
        List<String> warnings = new java.util.ArrayList<>(overlapping.stream().limit(MAX_CLASHES)
                .map(item -> "和日程「" + item.title() + "」（" + timeRange(item.startTime(), item.endTime()) + "）时间重叠")
                .toList());
        if (overlapping.size() > MAX_CLASHES) {
            warnings.add("另外还有 " + (overlapping.size() - MAX_CLASHES) + " 个日程时间重叠");
        }
        return warnings;
    }

    /** How a schedule item looks; a rollback only runs while it still looks exactly like this. */
    static ObjectNode snapshot(ObjectMapper mapper, ScheduleDtos.ScheduleItemResponse item) {
        ObjectNode node = mapper.createObjectNode();
        node.put("title", item.title());
        node.put("start", item.startTime() == null ? null : item.startTime().toString());
        node.put("end", item.endTime() == null ? null : item.endTime().toString());
        node.put("location", item.location());
        node.put("notes", item.notes());
        node.put("status", item.status());
        return node;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    static LocalDateTime time(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDateTime.parse(value);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
